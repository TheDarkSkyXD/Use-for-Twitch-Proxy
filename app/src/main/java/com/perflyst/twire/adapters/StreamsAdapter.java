package com.perflyst.twire.adapters;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.cardview.widget.CardView;

import com.perflyst.twire.R;
import com.perflyst.twire.activities.ChannelActivity;
import com.perflyst.twire.activities.stream.LiveStreamActivity;
import com.perflyst.twire.misc.OnlineSince;
import com.perflyst.twire.model.ChannelInfo;
import com.perflyst.twire.model.StreamInfo;
import com.perflyst.twire.model.UserInfo;
import com.perflyst.twire.service.Service;
import com.perflyst.twire.service.Settings;
import com.perflyst.twire.utils.Execute;
import com.perflyst.twire.views.recyclerviews.AutoSpanRecyclerView;

/**
 * Created by Sebastian Rask on 11-04-2016.
 */
class StreamViewHolder extends MainActivityAdapter.ElementsViewHolder {
    final ImageView vPreviewImage;
    final TextView vDisplayName, vTitle, vGame, vOnlineSince;
    final View sharedPadding;
    private final CardView vCard;
    //private TextView vOnlineSince;

    StreamViewHolder(View v) {
        super(v);
        vCard = v.findViewById(R.id.cardView_online_streams);
        vPreviewImage = v.findViewById(R.id.image_stream_preview);
        vDisplayName = v.findViewById(R.id.displayName);
        vTitle = v.findViewById(R.id.stream_title);
        vGame = v.findViewById(R.id.stream_game_and_viewers);
        sharedPadding = v.findViewById(R.id.shared_padding);
        vOnlineSince = v.findViewById(R.id.stream_online_since);
    }

    @Override
    public ImageView getPreviewView() {
        return vPreviewImage;
    }

    @Override
    public CharSequence getTargetsKey() {
        return vDisplayName.getText();
    }

    @Override
    public View getElementWrapper() {
        return vCard;
    }
}

public class StreamsAdapter extends MainActivityAdapter<StreamInfo, StreamViewHolder> {
    private final int topMargin, bottomMargin;
    private final Activity activity;
    private int rightMargin, leftMargin;

    public StreamsAdapter(AutoSpanRecyclerView recyclerView, Activity aActivity) {
        super(recyclerView, aActivity);
        activity = aActivity;
        rightMargin = (int) getContext().getResources().getDimension(R.dimen.stream_card_right_margin);
        bottomMargin = (int) getContext().getResources().getDimension(R.dimen.stream_card_bottom_margin);
        topMargin = (int) getContext().getResources().getDimension(R.dimen.stream_card_top_margin);
        leftMargin = (int) getContext().getResources().getDimension(R.dimen.stream_card_left_margin);
    }


    @Override
    StreamViewHolder getElementsViewHolder(View view) {
        return new StreamViewHolder(view);
    }

    @SuppressLint("NewApi")
    @Override
    void handleElementOnClick(View view) {
        int itemPosition = getRecyclerView().getChildAdapterPosition(view);

        if (itemPosition < 0 || getElements().size() <= itemPosition) {
            return;
        }

        StreamInfo item = getElements().get(itemPosition);
        Intent intent = LiveStreamActivity.createLiveStreamIntent(item, true, getContext());

        View sharedView = view.findViewById(R.id.image_stream_preview);
        sharedView.setTransitionName(getContext().getString(R.string.stream_preview_transition));
        final ActivityOptions options = ActivityOptions.makeSceneTransitionAnimation(
                activity, sharedView, getContext().getString(R.string.stream_preview_transition));
        activity.startActivity(intent, options.toBundle());
    }

    @Override
    protected void handleElementOnLongClick(final View view) {
        int itemPosition = getRecyclerView().getChildAdapterPosition(view);

        StreamInfo item = getElements().get(itemPosition);
        UserInfo userInfo = item.userInfo;

        Execute.background(() -> {
            ChannelInfo mChannelInfo = Service.getStreamerInfoFromUserId(userInfo.getUserId());

            Intent intent = new Intent(getContext(), ChannelActivity.class);
            intent.putExtra(getContext().getString(R.string.channel_info_intent_object), mChannelInfo);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            getContext().startActivity(intent);
            activity.overridePendingTransition(R.anim.slide_in_right_anim, R.anim.fade_out_semi_anim);
        });
    }

    @Override
    void setViewLayoutParams(View view, int position) {
        int spanCount = getRecyclerView().getSpanCount();
        // If this card ISN'T the end of a row - Half the right margin
        rightMargin = (position + 1) % spanCount != 0
                ? (int) getContext().getResources().getDimension(R.dimen.stream_card_margin_half)
                : (int) getContext().getResources().getDimension(R.dimen.stream_card_right_margin);

        // If the previous card ISN'T the end of a row, this card ISN'T be the start of a row - Half the left margin
        leftMargin = position % spanCount != 0
                ? (int) getContext().getResources().getDimension(R.dimen.stream_card_margin_half)
                : (int) getContext().getResources().getDimension(R.dimen.stream_card_left_margin);

        // Set the correct margin of the card
        ViewGroup.MarginLayoutParams marginParams = new ViewGroup.MarginLayoutParams(view.getLayoutParams());

        if (position < spanCount) { // Give extra top margin to cards in the first row to make sure it doesn't get overlapped by the toolbar
            marginParams.setMargins(leftMargin, getTopMargin(), rightMargin, bottomMargin);
        } else {
            marginParams.setMargins(leftMargin, topMargin, rightMargin, bottomMargin);
        }

        view.setLayoutParams(new RelativeLayout.LayoutParams(marginParams));
    }

    @Override
    protected void adapterSpecial(StreamViewHolder viewHolder) {
        double previewImageWidth = getContext().getResources().getDisplayMetrics().widthPixels / (double) getRecyclerView().getSpanCount() - leftMargin - rightMargin;
        double previewImageHeight = previewImageWidth / (16 / 9.0);
        viewHolder.vPreviewImage.setMinimumHeight((int) previewImageHeight);
    }

    @Override
    void setViewData(StreamInfo element, StreamViewHolder viewHolder) {
        DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        viewHolder.vPreviewImage.getLayoutParams().width = metrics.widthPixels;

        String viewers = getContext().getString(R.string.my_streams_cell_current_viewers, element.currentViewers);
        String gameAndViewers = viewers + " - " + element.game;

        viewHolder.vDisplayName.setText(element.userInfo.getDisplayName());
        viewHolder.vTitle.setText(element.title);
        viewHolder.vGame.setText(gameAndViewers);
        viewHolder.vOnlineSince.setText(OnlineSince.getOnlineSince(element.startedAt));
        viewHolder.vPreviewImage.setVisibility(View.VISIBLE);
    }

    @Override
    int getLayoutResource() {
        return R.layout.cell_stream;
    }

    @Override
    int getCornerRadiusResource() {
        return R.dimen.stream_card_corner_radius;
    }

    @Override
    int getTopMarginResource() {
        return R.dimen.stream_card_first_top_margin;
    }

    @Override
    int calculateCardWidth() {
        return getRecyclerView().getElementWidth();
    }

    @Override
    public String initElementStyle() {
        return Settings.getAppearanceStreamStyle();
    }

    @Override
    protected void setExpandedStyle(StreamViewHolder viewHolder) {
        viewHolder.vTitle.setVisibility(View.VISIBLE);
        viewHolder.vGame.setVisibility(View.VISIBLE);
        viewHolder.sharedPadding.setVisibility(View.VISIBLE);
    }

    @Override
    protected void setNormalStyle(StreamViewHolder viewHolder) {
        viewHolder.vTitle.setVisibility(View.GONE);
        viewHolder.vGame.setVisibility(View.VISIBLE);
        viewHolder.sharedPadding.setVisibility(View.VISIBLE);
    }

    @Override
    protected void setCollapsedStyle(StreamViewHolder viewHolder) {
        viewHolder.vTitle.setVisibility(View.GONE);
        viewHolder.vGame.setVisibility(View.GONE);
        viewHolder.sharedPadding.setVisibility(View.GONE);
    }
}
