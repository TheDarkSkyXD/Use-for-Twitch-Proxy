package com.perflyst.twire.chat;

/*
 * Created by SebastianRask on 03-03-2016.
 */

import android.content.Context;
import android.os.SystemClock;
import android.util.SparseArray;

import com.google.common.collect.ImmutableSetMultimap;
import com.perflyst.twire.model.Badge;
import com.perflyst.twire.model.ChatMessage;
import com.perflyst.twire.model.Emote;
import com.perflyst.twire.model.IRCMessage;
import com.perflyst.twire.model.UserInfo;
import com.perflyst.twire.service.Service;
import com.perflyst.twire.service.Settings;
import com.perflyst.twire.utils.Execute;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;

import javax.net.ssl.SSLSocketFactory;

import timber.log.Timber;

public class ChatManager implements Runnable {
    public static ChatManager instance = null;

    public static ImmutableSetMultimap<String, Badge> ffzBadgeMap;
    private double currentProgress = -1;
    private String cursor = "";
    private boolean seek = false;
    private double previousProgress;
    private final String user;
    private final String password;
    private final UserInfo channel;
    private final String vodId;
    private final ChatCallback callback;
    private final ChatEmoteManager mEmoteManager;
    private final Map<String, Map<String, Badge>> globalBadges = new HashMap<>();
    private final Map<String, Map<String, Badge>> channelBadges = new HashMap<>();
    // Default Twitch Chat connect IP/domain and port
    private String twitchChatServer = "irc.chat.twitch.tv";
    // Port 6667 for unsecure connection | 6697 for SSL
    private int twitchChatPortunsecure = 6667;
    private int twitchChatPortsecure = 6697;
    private int twitchChatPort;

    private final Object vodLock = new Object();
    private double nextCommentOffset = 0;

    private BufferedWriter writer;
    private boolean isStopping;
    // Data about the user and how to display his/hers message
    private String userDisplayName;
    private String userColor;
    private Map<String, String> userBadges;
    // Data about room state
    private boolean chatIsR9kmode;
    private boolean chatIsSlowmode;
    private boolean chatIsSubsonlymode;

    private Context context;

    public ChatManager(Context aContext, UserInfo aChannel, String aVodId, ChatCallback aCallback) {
        instance = this;
        this.context = aContext;
        Settings appSettings = new Settings(aContext);
        mEmoteManager = new ChatEmoteManager(aChannel, appSettings);

        Timber.d("Login with main Account: %s", appSettings.getChatAccountConnect());

        if (appSettings.isLoggedIn() && appSettings.getChatAccountConnect()) { // if user is logged in ...
            // ... use their credentials
            Timber.d("Using user credentials for chat login.");

            user = appSettings.getGeneralTwitchName();
            password = "oauth:" + appSettings.getGeneralTwitchAccessToken();
        } else {
            // ... else: use anonymous credentials
            Timber.d("Using anonymous credentials for chat login.");

            user = "justinfan" + getRandomNumber(10000, 99999);
            password = "SCHMOOPIIE";
        }

        channel = aChannel;
        vodId = aVodId;
        callback = aCallback;

        //Set the Port Setting
        if (appSettings.getChatEnableSSL())
            twitchChatPort = twitchChatPortsecure;
        else {
            twitchChatPort = twitchChatPortunsecure;
        }
        Timber.d("Use SSL Chat Server: %s", appSettings.getChatEnableSSL());

        nextCommentOffset = 0;
    }

    public void updateVodProgress(long aCurrentProgress, boolean aSeek) {
        currentProgress = aCurrentProgress / 1000f;
        seek |= aSeek;

        // Only notify the thread when there's work to do.
        if (!aSeek && currentProgress < nextCommentOffset) return;

        synchronized (vodLock) {
            vodLock.notify();
        }
    }

    public void setPreviousProgress() {
        previousProgress = currentProgress;
        cursor = "";
    }

    @Override
    public void run() {
        isStopping = false;
        Timber.d("Trying to start chat " + channel.getLogin() + " for user " + user);
        mEmoteManager.loadCustomEmotes(() -> onUpdate(UpdateType.ON_CUSTOM_EMOTES_FETCHED));

        readBadges("https://api.twitch.tv/helix/chat/badges/global/", globalBadges);
        readBadges("https://api.twitch.tv/helix/chat/badges?broadcaster_id=" + channel.getUserId(), channelBadges);
        readFFZBadges();

        if (vodId == null) {
            connect(twitchChatServer, twitchChatPort);
        } else {
            processVodChat();
        }
    }

    protected void onUpdate(UpdateType type) {
        Execute.ui(() -> {
            switch (type) {
                case ON_CONNECTED:
                    callback.onConnected();
                    break;
                case ON_CONNECTING:
                    callback.onConnecting();
                    break;
                case ON_CONNECTION_FAILED:
                    callback.onConnectionFailed();
                    break;
                case ON_RECONNECTING:
                    callback.onReconnecting();
                    break;
                case ON_ROOMSTATE_CHANGE:
                    callback.onRoomstateChange(chatIsR9kmode, chatIsSlowmode, chatIsSubsonlymode);
                    break;
                case ON_CUSTOM_EMOTES_FETCHED:
                    callback.onCustomEmoteIdFetched(
                            mEmoteManager.getChannelCustomEmotes(), mEmoteManager.getGlobalCustomEmotes()
                    );
                    break;
            }
        });
    }

    protected void onMessage(ChatMessage message) {
        Execute.ui(() -> callback.onMessage(message));
    }


    /**
     * Connect to twitch with the users twitch name and oauth key.
     * Joins the chat hashChannel.
     * Sends request to retrieve emote id and positions as well as username color
     * Handles parsing messages, pings and disconnects.
     * Inserts emotes, subscriber, turbo and mod drawables into messages. Also Colors the message username by the user specified color.
     * When a message has been parsed it is sent via the callback interface.
     */
    private void connect(String address, int port) {
        try {
            Timber.d("Chat connecting to " + address + ":" + port);
            Socket socket;
            // if we don`t use the SSL Port then create a default socket
            if (port != twitchChatPortsecure) {
                socket = new Socket(address, port);
            } else {
                // if we use the SSL Port then create a SSL Socket
                // https://stackoverflow.com/questions/13874387/create-app-with-sslsocket-java
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                socket = factory.createSocket(address, port);
            }

            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            writer.write("PASS " + password + "\r\n");
            writer.write("NICK " + user + "\r\n");
            writer.write("USER " + user + " \r\n");
            writer.flush();

            String line;
            while ((line = reader.readLine()) != null) {
                if (isStopping) {
                    leaveChannel();
                    Timber.d("Stopping chat for %s", channel.getLogin());
                    break;
                }

                IRCMessage ircMessage = IRCMessage.parse(line);
                if (ircMessage != null) {
                    handleIRC(ircMessage);
                } else if (line.contains("004 " + user + " :")) {
                    Timber.d("<%s", line);
                    Timber.d("Connected >> " + user + " ~ irc.twitch.tv");
                    onUpdate(UpdateType.ON_CONNECTED);
                    sendRawMessage("CAP REQ :twitch.tv/tags twitch.tv/commands");
                    sendRawMessage("JOIN #" + channel.getLogin() + "\r\n");
                } else if (line.startsWith("PING")) { // Twitch wants to know if we are still here. Send PONG and Server info back
                    handlePing(line);
                } else if (line.toLowerCase().contains("disconnected")) {
                    Timber.e("Disconnected - trying to reconnect");
                    onUpdate(UpdateType.ON_RECONNECTING);
                    connect(address, port); //ToDo: Test if chat keeps playing if connection is lost
                } else if (line.contains("NOTICE * :Error logging in")) {
                    onUpdate(UpdateType.ON_CONNECTION_FAILED);
                } else {
                    Timber.d("<%s", line);
                }
            }

            // If we reach this line then the socket closed but chat wasn't stopped, so reconnect.
            if (!isStopping) connect(address, port);
        } catch (IOException e) {
            e.printStackTrace();

            onUpdate(UpdateType.ON_CONNECTION_FAILED);
            SystemClock.sleep(2500);
            onUpdate(UpdateType.ON_RECONNECTING);
            connect(address, port);
        }
    }

    private static class VODComment {
        public final double contentOffset;
        public final JSONObject data;

        private VODComment(double contentOffset, JSONObject data) {
            this.contentOffset = contentOffset;
            this.data = data;
        }
    }

    private void processVodChat() {
                try {
            onUpdate(UpdateType.ON_CONNECTED);

            // Make sure that current progress has been set.
            synchronized (vodLock) {
                while (currentProgress == -1 && !isStopping) vodLock.wait();
            }

            Queue<VODComment> downloadedComments = new LinkedList<>();
            boolean reconnecting = false;
            boolean justSeeked = false;
            while (!isStopping) {
                if (seek) {
                    seek = false;
                    cursor = "";
                    downloadedComments.clear();
                    previousProgress = 0;
                    justSeeked = true;
                }

                VODComment comment = downloadedComments.peek();
                if (comment == null) {
                    JSONObject dataObject = Service.graphQL("VideoCommentsByOffsetOrCursor", "b70a3591ff0f4e0313d126c6a1502d79a1c02baebb288227c582044aa76adf6a", new HashMap<>() {{
                        put("videoID", vodId);
                        if (cursor.isEmpty()) put("contentOffsetSeconds", (int) currentProgress);
                        else put("cursor", cursor);
                    }});

                    if (dataObject == null) {
                        reconnecting = true;
                        onUpdate(UpdateType.ON_RECONNECTING);
                        SystemClock.sleep(2500);
                        continue;
                    } else if (reconnecting) {
                        reconnecting = false;
                        onUpdate(UpdateType.ON_CONNECTED);
                    }

                    if (dataObject.getJSONObject("video").isNull("comments")) {
                        cursor = "";
                        continue;
                    }

                    JSONObject commentsObject = dataObject.getJSONObject("video").getJSONObject("comments");
                    JSONArray comments = commentsObject.getJSONArray("edges");

                    for (int i = 0; i < comments.length(); i++) {
                        JSONObject commentJSON = comments.getJSONObject(i).getJSONObject("node");
                        int contentOffset = commentJSON.getInt("contentOffsetSeconds");
                        // Don't show previous comments and don't show comments that came before the current progress unless we just seeked.
                        if (contentOffset < previousProgress || contentOffset < currentProgress && !justSeeked)
                            continue;

                        // Sometimes the commenter is null, Twitch doesn't show them so we won't either.
                        if (commentJSON.isNull("commenter"))
                            continue;

                        downloadedComments.add(new VODComment(contentOffset, commentJSON));
                    }

                    justSeeked = false;

                    JSONObject pageInfo = commentsObject.getJSONObject("pageInfo");
                    boolean hasNextPage = pageInfo.getBoolean("hasNextPage");
                    // Assumption: If the VOD has no comments and no previous or next comments, there are no comments on the VOD.
                    if (comments.length() == 0 && !hasNextPage && !pageInfo.getBoolean("hasPreviousPage")) {
                        break;
                    }

                    if (hasNextPage) {
                        cursor = comments.getJSONObject(comments.length() - 1).getString("cursor");
                    } else if (downloadedComments.isEmpty()) {
                        // We've reached the end of the comments, nothing to do until the user seeks.
                        synchronized (vodLock) {
                            while (!seek && !isStopping) {
                                vodLock.wait();
                            }
                        }
                    }

                    comment = downloadedComments.peek();
                }

                if (seek || comment == null) {
                    continue;
                }

                nextCommentOffset = comment.contentOffset;
                synchronized (vodLock) {
                    while (currentProgress < nextCommentOffset && !seek && !isStopping) vodLock.wait();
                }

                // If the user seeked, don't display this comment since it would now be an old comment.
                if (seek) continue;

                JSONObject commenter = comment.data.getJSONObject("commenter");
                JSONObject message = comment.data.getJSONObject("message");

                Map<String, String> badges = new HashMap<>();
                if (message.has("userBadges")) {
                    JSONArray userBadgesArray = message.getJSONArray("userBadges");
                    for (int j = 0; j < userBadgesArray.length(); j++) {
                        JSONObject userBadge = userBadgesArray.getJSONObject(j);
                        String setID = userBadge.getString("setID");
                        String version = userBadge.getString("version");
                        if (setID.isEmpty() || version.isEmpty()) continue;

                        badges.put(setID, version);
                    }
                }

                String color = !message.isNull("userColor") ? message.getString("userColor") : null;
                String displayName = commenter.getString("displayName");

                StringBuilder bodyBuilder = new StringBuilder();
                JSONArray fragments = message.getJSONArray("fragments");
                Map<Integer, Emote> emotes = new HashMap<>();
                for (int i = 0; i < fragments.length(); i++) {
                    JSONObject fragment = fragments.getJSONObject(i);
                    String text = fragment.getString("text");

                    JSONObject emote = fragment.optJSONObject("emote");
                    if (emote != null) {
                        emotes.put(bodyBuilder.length(), Emote.Twitch(text, emote.getString("emoteID")));
                    }

                    bodyBuilder.append(text);
                }

                String body = bodyBuilder.toString();
                emotes.putAll(mEmoteManager.findCustomEmotes(body));

                //Pattern.compile(Pattern.quote(userDisplayName), Pattern.CASE_INSENSITIVE).matcher(message).find();

                ChatMessage chatMessage = new ChatMessage(body, displayName, color, getBadges(badges), emotes, false);
                onMessage(chatMessage);

                downloadedComments.poll();
            }
        } catch (Exception e) {
            e.printStackTrace();

            onUpdate(UpdateType.ON_CONNECTION_FAILED);
            SystemClock.sleep(2500);
            onUpdate(UpdateType.ON_RECONNECTING);
            processVodChat();
        }

        currentProgress = -1;
    }

    public void handleIRC(IRCMessage message) {
        switch (message.command) {
            case "PRIVMSG":
            case "USERNOTICE":
                handleMessage(message);
                break;
            case "USERSTATE":
                if (userDisplayName == null)
                    handleUserstate(message);
                break;
            case "ROOMSTATE":
                handleRoomstate(message);
                break;
            case "NOTICE":
                handleNotice(message);
                break;
            case "CLEARCHAT":
                Execute.ui(() -> callback.onClear(message.content));
                break;
            case "CLEARMSG":
                Execute.ui(() -> callback.onClear(message.tags.get("target-msg-id")));
                break;
            case "JOIN":
                break;
            default:
                Timber.e("Unhandled command type: %s", message.command);
                break;
        }
    }

    private void handleNotice(IRCMessage message) {
        String msgId = message.tags.get("msg-id");
        switch (msgId) {
            case "subs_on":
                chatIsSubsonlymode = true;
                break;
            case "subs_off":
                chatIsSubsonlymode = false;
                break;
            case "slow_on":
                chatIsSlowmode = true;
                break;
            case "slow_off":
                chatIsSlowmode = false;
                break;
            case "r9k_on":
                chatIsR9kmode = true;
                break;
            case "r9k_off":
                chatIsR9kmode = false;
                break;
        }

        onUpdate(UpdateType.ON_ROOMSTATE_CHANGE);
    }

    /**
     * Parses the received line and gets the roomstate.
     * If the roomstate has changed since last check variables are changed and the chatfragment is notified
     */
    private void handleRoomstate(IRCMessage message) {
        boolean roomstateChanged = false;

        if( message.tags.get("r9k") != null) {
            chatIsR9kmode = message.tags.get("r9k").equals("1");
            roomstateChanged = true;
        }
        if( message.tags.get("slow") != null) {
            chatIsSlowmode = !message.tags.get("slow").equals("0");
            roomstateChanged = true;
        }
        if( message.tags.get("subs-only") != null) {
            chatIsSubsonlymode = message.tags.get("subs-only").equals("1");
            roomstateChanged = true;
        }

        // If the one of the roomstate types have changed notify the chatfragment
        if (roomstateChanged) {
            onUpdate(UpdateType.ON_ROOMSTATE_CHANGE);
        }
    }

    /**
     * Parses the received line and saves data such as the users color, if the user is mod, subscriber or turbouser
     */
    private void handleUserstate(IRCMessage message) {
        userBadges = new HashMap<>();
        String badgeString = message.tags.get("badges");
        if (badgeString != null && !badgeString.isEmpty()) {
            for (String badge : badgeString.split(",")) {
                String[] parts = badge.split("/");
                userBadges.put(parts[0], parts[1]);
            }
        }

        userColor = message.tags.get("color");
        userDisplayName = message.tags.get("display-name");
        callback.onEmoteSetsFetched(message.tags.get("emote-sets").split(","));
    }

    /**
     * Parses and builds retrieved messages.
     * Sends build message back via callback.
     */
    private void handleMessage(IRCMessage message) {
        Map<String, String> tags = message.tags;
        Map<String, String> badges = new HashMap<>();
        String badgesString = tags.get("badges");
        if (badgesString != null) {
            for (String badge : badgesString.split(",")) {
                String[] parts = badge.split("/");
                badges.put(parts[0], parts[1]);
            }
        }
        String color = tags.get("color");
        String displayName = tags.get("display-name");
        String content = message.content;
        Map<Integer, Emote> emotes = mEmoteManager.findTwitchEmotes(message.tags.get("emotes"), content);
        emotes.putAll(mEmoteManager.findCustomEmotes(content));
        //Pattern.compile(Pattern.quote(userDisplayName), Pattern.CASE_INSENSITIVE).matcher(message).find();

        ChatMessage chatMessage = new ChatMessage(content, displayName, color, getBadges(badges), emotes, false);
        chatMessage.setID(tags.get("id"));
        chatMessage.systemMessage = tags.getOrDefault("system-msg", "");

        if (content.contains("@" + getUserDisplayName())) {
            Timber.d("Highlighting message with mention: %s", content);
            chatMessage.setHighlight(true);
        }

        onMessage(chatMessage);
    }

    /**
     * Sends a PONG with the connected twitch server, as specified by Twitch IRC API.
     */
    private void handlePing(String line) throws IOException {
        writer.write("PONG " + line.substring(5) + "\r\n");
        writer.flush();
    }

    /**
     * Sends an non manipulated String message to Twitch.
     */
    private void sendRawMessage(String message) {
        try {
            writer.write(message + " \r\n");
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * Makes the ChatManager stop retrieving messages.
     */
    public void stop() {
        isStopping = true;

        synchronized (vodLock) {
            vodLock.notify();
        }
    }

    /**
     * Send a message to a hashChannel on Twitch (Don't need to be on that hashChannel)
     *
     * @param message The message that will be sent
     */
    public void sendMessage(final String message) {
        try {
            if (writer != null) {
                writer.write("PRIVMSG #" + channel.getLogin() + " :" + message + "\r\n");
                writer.flush();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Leaves the current hashChannel
     */
    private void leaveChannel() {
        sendRawMessage("PART #" + channel.getLogin());
    }

    private void readBadges(String url, Map<String, Map<String, Badge>> badgeMap) {
        try {
            JSONArray globalBadgeArray = new JSONObject(Service.urlToJSONStringHelix(url, context)).getJSONArray("data");
            for (int i = 0; i < globalBadgeArray.length(); i++ ) {
                String badgeSet = globalBadgeArray.getJSONObject(i).getString("set_id");
                Map<String, Badge> versionMap = new HashMap<>();

                badgeMap.put(badgeSet, versionMap);

                JSONArray versions = globalBadgeArray.getJSONObject(i).getJSONArray("versions");
                for (int j = 0; j < versions.length(); j++) {
                    JSONObject versionObject = versions.getJSONObject(j);
                    String version = versionObject.getString("id");
                    SparseArray<String> urls = new SparseArray<>();
                    urls.put(1, versionObject.getString("image_url_1x"));
                    urls.put(2, versionObject.getString("image_url_2x"));
                    urls.put(4, versionObject.getString("image_url_4x"));

                    versionMap.put(version, new Badge(badgeSet, urls));
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void readFFZBadges() {
        ImmutableSetMultimap.Builder<String, Badge> mapBuilder = ImmutableSetMultimap.builder();

        try {
            JSONObject topObject = new JSONObject(Service.urlToJSONString("https://api.frankerfacez.com/v1/badges"));
            JSONArray badges = topObject.getJSONArray("badges");
            JSONObject users = topObject.getJSONObject("users");
            for (int badgeIndex = 0; badgeIndex < badges.length(); badgeIndex++) {
                JSONObject badgeJSON = badges.getJSONObject(badgeIndex);

                SparseArray<String> urls = new SparseArray<>();
                JSONObject urlsObject = badgeJSON.getJSONObject("urls");
                for (Iterator<String> iterator = urlsObject.keys(); iterator.hasNext(); ) {
                    String size = iterator.next();
                    urls.put(Integer.parseInt(size), urlsObject.getString(size));
                }

                Badge badge = new Badge(badgeJSON.getString("name"), urls, badgeJSON.getString("color"), badgeJSON.isNull("replaces") ? null : badgeJSON.getString("replaces"));
                JSONArray badgeUsers = users.getJSONArray(badgeJSON.getString("id"));
                for (int userIndex = 0; userIndex < badgeUsers.length(); userIndex++) {
                    mapBuilder.put(badgeUsers.getString(userIndex), badge);
                }
            }

            ffzBadgeMap = mapBuilder.build();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public String getUserDisplayName() {
        return userDisplayName;
    }

    public String getUserColor() {
        return userColor;
    }

    public Map<String, String> getUserBadges() {
        return userBadges;
    }

    private Badge getBadge(String badgeSet, String version) {
        Map<String, Badge> channelSet = channelBadges.get(badgeSet);
        if (channelSet != null && channelSet.get(version) != null)
            return channelSet.get(version);

        Map<String, Badge> globalSet = globalBadges.get(badgeSet);
        if (globalSet != null && globalSet.get(version) != null)
            return globalSet.get(version);

        Timber.e("Badge failed to load: \"" + badgeSet + "\" \"" + version + "\"");
        return null;
    }

    public List<Badge> getBadges(Map<String, String> badges) {
        List<Badge> badgeObjects = new ArrayList<>();
        for (Map.Entry<String, String> entry : badges.entrySet()) {
            badgeObjects.add(getBadge(entry.getKey(), entry.getValue()));
        }

        return badgeObjects;
    }

    private int getRandomNumber(int min, int max) {
        return new Random().nextInt(max - min + 1) + min;
    }

    public interface ChatCallback {
        void onMessage(ChatMessage message);

        void onClear(String target);

        void onConnecting();

        void onReconnecting();

        void onConnected();

        void onConnectionFailed();

        void onRoomstateChange(boolean isR9K, boolean isSlow, boolean isSubsOnly);

        void onCustomEmoteIdFetched(List<Emote> channel, List<Emote> global);

        void onEmoteSetsFetched(String[] emoteSets);
    }

    public enum UpdateType {
        ON_MESSAGE,
        ON_CONNECTING,
        ON_RECONNECTING,
        ON_CONNECTED,
        ON_CONNECTION_FAILED,
        ON_ROOMSTATE_CHANGE,
        ON_CUSTOM_EMOTES_FETCHED
    }
}

