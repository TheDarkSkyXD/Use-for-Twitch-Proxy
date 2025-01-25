package com.perflyst.twire.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Patterns;

import androidx.appcompat.app.AppCompatActivity;

import com.perflyst.twire.R;
import com.perflyst.twire.activities.stream.LiveStreamActivity;
import com.perflyst.twire.activities.stream.VODActivity;
import com.perflyst.twire.model.ChannelInfo;
import com.perflyst.twire.model.StreamInfo;
import com.perflyst.twire.model.VideoOnDemand;
import com.perflyst.twire.service.DialogService;
import com.perflyst.twire.service.JSONService;
import com.perflyst.twire.service.Service;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;

public class DeepLinkActivity extends AppCompatActivity {
    private int errorMessage = R.string.router_unknown_error;

    @Override
    public void onCreate(Bundle savedInstance) {
        super.onCreate(savedInstance);

        Uri data = getUri(getIntent());
        List<String> params = new LinkedList<>(data.getPathSegments());

        // twitch.tv/<channel>/video/<id> -> twitch.tv/videos/<id>
        if (params.size() == 3 && (params.get(1).equals("video") || params.get(1).equals("v"))) {
            params.set(1, "videos");
            params.remove(0);
        }

        int paramSize = params.size();

        new Thread(() -> {
            Intent intent = null;
            try {
                intent = getNewIntent(params, paramSize);
            } catch (Exception exception) {
                exception.printStackTrace();
            }

            if (intent == null) {
                runOnUiThread(() -> DialogService.getRouterErrorDialog(this, errorMessage).show());
            } else {
                startActivity(intent);
            }
        }).start();
    }

    Intent getNewIntent(List<String> params, int paramSize) throws Exception {
        Intent intent = null;
        if (paramSize == 2 && params.get(0).equals("videos")) { // twitch.tv/videos/<id>
            errorMessage = R.string.router_vod_error;

            JSONArray jsonArray = new JSONObject(Service.urlToJSONStringHelix("https://api.twitch.tv/helix/videos?id=" + params.get(1), this)).getJSONArray("data");
            JSONObject jsonObject = jsonArray.getJSONObject(0);
            VideoOnDemand vod = JSONService.getVod(jsonObject);

            String channel_request_url = "https://api.twitch.tv/helix/users?id=" + jsonObject.getString("user_id");
            String channel_string = Service.urlToJSONStringHelix(channel_request_url, this);
            JSONObject channel_object = new JSONObject(channel_string);
            JSONArray temp_array = channel_object.getJSONArray("data");
            JSONObject JSONChannel = temp_array.getJSONObject(0);
            vod.setChannelInfo(JSONService.getStreamerInfo(this, JSONChannel));

            intent = VODActivity.createVODIntent(vod, this, false);
        } else if (paramSize == 1) { // twitch.tv/<channel>
            String channel_request_url = "https://api.twitch.tv/helix/users?login=" + params.get(0);
            String channel_string = Service.urlToJSONStringHelix(channel_request_url, this);
            JSONObject channel_object = new JSONObject(channel_string);
            JSONArray temp_array;
            try {
                temp_array = channel_object.getJSONArray("data");
            } catch (Exception e) {
                errorMessage = R.string.router_nonexistent_user;
                return null;
            }

            JSONObject JSONChannel = temp_array.getJSONObject(0);

            errorMessage = R.string.router_channel_error;

            String userID = JSONChannel.getString("id");
            JSONObject streamsObject = new JSONObject(Service.urlToJSONStringHelix("https://api.twitch.tv/helix/streams?user_id=" + userID, this));
            JSONArray real_stream = streamsObject.getJSONArray("data");
            JSONObject streamObject = real_stream.length() == 0 ? null : real_stream.getJSONObject(0);

            if (streamObject != null) {
                StreamInfo stream = JSONService.getStreamInfo(this, streamObject, false);
                intent = LiveStreamActivity.createLiveStreamIntent(stream, false, this);
            } else {
                // If we can't load the stream, try to show the user's channel instead.
                ChannelInfo info = Service.getStreamerInfoFromUserId(userID, this);
                if (info != null) {
                    intent = new Intent(this, ChannelActivity.class);
                    intent.putExtra(getString(R.string.channel_info_intent_object), info);
                }
            }
        }

        return intent;
    }

    Uri getUri(Intent intent) {
        if (intent.getData() != null) {
            return intent.getData();
        } else if (intent.getStringExtra(Intent.EXTRA_TEXT) != null) {
            return getUriFromString(intent.getStringExtra(Intent.EXTRA_TEXT));
        }

        return null;
    }

    Uri getUriFromString(String string) {
        Matcher matcher = Patterns.WEB_URL.matcher(string);
        if (matcher.find()) {
            return Uri.parse(matcher.group(0));
        }

        return null;
    }
}
