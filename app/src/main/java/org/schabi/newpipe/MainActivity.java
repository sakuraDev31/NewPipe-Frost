/*
 * Created by Christian Schabesberger on 02.08.16.
 * <p>
 * Copyright (C) Christian Schabesberger 2016 <chris.schabesberger@mailbox.org>
 * DownloadActivity.java is part of NewPipe.
 * <p>
 * NewPipe is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * NewPipe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with NewPipe.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.schabi.newpipe;

import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.Rational;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentContainerView;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import com.google.android.material.bottomsheet.BottomSheetBehavior;


import org.schabi.newpipe.databinding.ActivityMainBinding;
import org.schabi.newpipe.databinding.ToolbarLayoutBinding;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.fragments.BackPressable;
import org.schabi.newpipe.fragments.MainFragment;
import org.schabi.newpipe.fragments.detail.VideoDetailFragment;
import org.schabi.newpipe.download.GlassDownloadFragment;
import org.schabi.newpipe.fragments.list.comments.CommentRepliesFragment;
import org.schabi.newpipe.fragments.list.search.SearchFragment;
import org.schabi.newpipe.frost.LibraryHubFragment;
import org.schabi.newpipe.local.feed.notifications.NotificationWorker;
import org.schabi.newpipe.local.subscription.SubscriptionFragment;
import org.schabi.newpipe.player.Player;
import org.schabi.newpipe.player.PlayerType;
import org.schabi.newpipe.player.event.OnKeyDownListener;
import org.schabi.newpipe.player.helper.PlayerHolder;
import org.schabi.newpipe.player.playqueue.PlayQueue;
import org.schabi.newpipe.settings.UpdateSettingsFragment;
import org.schabi.newpipe.settings.migration.MigrationManager;
import org.schabi.newpipe.util.Constants;
import org.schabi.newpipe.util.DeviceUtils;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.PermissionHelper;
import org.schabi.newpipe.util.ReleaseVersionUtil;
import org.schabi.newpipe.util.SerializedCache;
import org.schabi.newpipe.util.ServiceHelper;
import org.schabi.newpipe.util.StateSaver;
import org.schabi.newpipe.util.ThemeHelper;
import org.schabi.newpipe.util.external_communication.ShareUtils;
import org.schabi.newpipe.views.FocusOverlayView;
import org.schabi.newpipe.views.glass.AdaptiveGlassNavController;
import org.schabi.newpipe.views.glass.PlayerSheetController;


import android.content.res.Configuration;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    @SuppressWarnings("ConstantConditions")
    public static final boolean DEBUG = !BuildConfig.BUILD_TYPE.equals("release");

    private ActivityMainBinding mainBinding;
    private ToolbarLayoutBinding toolbarLayoutBinding;
    private AdaptiveGlassNavController adaptiveNavController;
    private PlayerSheetController playerSheetController;

    private BroadcastReceiver broadcastReceiver;

    public static final String KEY_IS_IN_BACKGROUND = "is_in_background";

    private SharedPreferences sharedPreferences;
    private SharedPreferences.Editor sharedPrefEditor;
    /*//////////////////////////////////////////////////////////////////////////
    // Activity's LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        if (DEBUG) {
            Log.d(TAG, "onCreate() called with: "
                    + "savedInstanceState = [" + savedInstanceState + "]");
        }

        Localization.migrateAppLanguageSettingIfNecessary(getApplicationContext());
        ThemeHelper.setDayNightMode(this);
        ThemeHelper.setTheme(this, ServiceHelper.getSelectedServiceId(this));

        // Fixes text color turning black in dark/black mode:
        // https://github.com/TeamNewPipe/NewPipe/issues/12016
        // For further reference see: https://issuetracker.google.com/issues/37124582
        if (DeviceUtils.supportsWebView()) {
            try {
                new WebView(this);
            } catch (final Throwable e) {
                if (DEBUG) {
                    Log.e(TAG, "Failed to create WebView", e);
                }
            }
        }

        super.onCreate(savedInstanceState);
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        sharedPrefEditor = sharedPreferences.edit();

        mainBinding = ActivityMainBinding.inflate(getLayoutInflater());
        toolbarLayoutBinding = mainBinding.toolbarLayout;
        setContentView(mainBinding.getRoot());

        if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
            initFragments();
        }

        setSupportActionBar(toolbarLayoutBinding.toolbar);
        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(false);
        }
        setupHeaderActions();
        setupGroundedDock();
        setupLiquidStatusBar();
        if (savedInstanceState == null && Intent.ACTION_MAIN.equals(getIntent().getAction())) {
            showStartupBranding();
        }
        if (DeviceUtils.isTv(this)) {
            FocusOverlayView.setupFocusObserver(this);
        }
        openMiniPlayerUponPlayerStarted();

        if (PermissionHelper.checkPostNotificationsPermission(this,
                PermissionHelper.POST_NOTIFICATIONS_REQUEST_CODE)) {
            // Schedule worker for checking for new streams and creating corresponding notifications
            // if this is enabled by the user.
            NotificationWorker.initialize(this);
        }
        if (!UpdateSettingsFragment.wasUserAskedForConsent(this)
                && !App.getInstance().isFirstRun()
                && ReleaseVersionUtil.INSTANCE.isReleaseApk()) {
            UpdateSettingsFragment.askForConsentToUpdateChecks(this);
        }

        // ReleaseVersionUtil.INSTANCE.isReleaseApk() will be true only for main official build
        // We want every release build (nightly, nightly-refactor) to show the popup
        if (!DEBUG) {
            showKeepAndroidDialog();
            showApi23RequirementDialog();
        }

        MigrationManager.showUserInfoIfPresent(this);
    }

    private int systemBarTopInset = 0;

    public int getSystemBarTopInset() {
        return systemBarTopInset;
    }

    public int getTopHeaderTotalHeight() {
        if (toolbarLayoutBinding != null && toolbarLayoutBinding.getRoot().getHeight() > 0) {
            return toolbarLayoutBinding.getRoot().getHeight();
        }
        return systemBarTopInset + org.schabi.newpipe.util.DeviceUtils.dpToPx(56, this);
    }

    public int getFeedBottomContentPadding() {
        final int dockHeight = (mainBinding != null && mainBinding.groundedDockContainer != null
                && mainBinding.groundedDockContainer.getHeight() > 0)
                ? mainBinding.groundedDockContainer.getHeight()
                : org.schabi.newpipe.util.DeviceUtils.dpToPx(64, this);
        final boolean miniVisible = playerSheetController != null && playerSheetController.isCollapsed();
        final int miniHeight = miniVisible
                ? getResources().getDimensionPixelSize(R.dimen.mini_player_height)
                : 0;
        return dockHeight + miniHeight;
    }

    public void updateFeedsPadding() {
        final int topPad = getTopHeaderTotalHeight();
        final int bottomPad = getFeedBottomContentPadding();
        final FragmentManager fm = getSupportFragmentManager();
        for (final Fragment fragment : fm.getFragments()) {
            if (fragment instanceof org.schabi.newpipe.util.LiquidFeedPaddingListener) {
                ((org.schabi.newpipe.util.LiquidFeedPaddingListener) fragment)
                        .onFeedPaddingUpdated(topPad, bottomPad);
            }
        }
    }

    private void setupLiquidStatusBar() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        final var controller = androidx.core.view.WindowCompat.getInsetsController(
                getWindow(), mainBinding.getRoot());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(false);
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
                toolbarLayoutBinding.getRoot(), (view, insets) -> {
                    systemBarTopInset = insets.getInsets(
                            androidx.core.view.WindowInsetsCompat.Type.systemBars()).top;
                    view.setPadding(0, systemBarTopInset, 0, 0);
                    view.post(this::updateFeedsPadding);
                    return insets;
                });
        androidx.core.view.ViewCompat.requestApplyInsets(mainBinding.getRoot());
    }

    @Override
    protected void onPostCreate(final Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

        final App app = App.getInstance();

        if (sharedPreferences.getBoolean(app.getString(R.string.update_app_key), false)
                && sharedPreferences
                .getBoolean(app.getString(R.string.update_check_consent_key), false)) {
            // Start the worker which is checking all conditions
            // and eventually searching for a new version.
            NewVersionWorker.enqueueNewVersionCheckingWork(app, false);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        sharedPrefEditor.putBoolean(KEY_IS_IN_BACKGROUND, false).apply();
        Log.d(TAG, "App moved to foreground");
    }

    @Override
    protected void onStop() {
        dismissStartupBranding();
        super.onStop();
        sharedPrefEditor.putBoolean(KEY_IS_IN_BACKGROUND, true).apply();
        Log.d(TAG, "App moved to background");
    }

    @Override
    public void onUserLeaveHint() {
        super.onUserLeaveHint();
        enterPictureInPictureForPlayer();
    }

    /**
     * Enters system PiP for the active video player. This is also used by the former popup
     * action, so the action does not merely show a toast while leaving the video in the app.
     */
    public boolean enterPictureInPictureForPlayer() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || isInPictureInPictureMode()
                || !PlayerHolder.getInstance().isPlaying()
                || PlayerHolder.getInstance().getType() != PlayerType.MAIN) {
            return false;
        }
        if (playerSheetController != null && !playerSheetController.isExpanded()) {
            playerSheetController.expand();
        }
        final PictureInPictureParams.Builder params = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(16, 9));
        final Rect sourceRect = new Rect();
        if (mainBinding.fragmentPlayerHolder.getGlobalVisibleRect(sourceRect)) {
            params.setSourceRectHint(sourceRect);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setAutoEnterEnabled(true);
        }
        final boolean entered = enterPictureInPictureMode(params.build());
        if (entered) {
            Toast.makeText(this, R.string.picture_in_picture_playing_toast,
                    Toast.LENGTH_SHORT).show();
            mainBinding.getRoot().postDelayed(() -> moveTaskToBack(true), 100);
        }
        return entered;
    }

    @Override
    public void onPictureInPictureModeChanged(final boolean isInPictureInPictureMode) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode);
        if (mainBinding == null) {
            return;
        }
        final int visibility = isInPictureInPictureMode ? View.GONE : View.VISIBLE;
        toolbarLayoutBinding.getRoot().setVisibility(visibility);
        mainBinding.groundedDockContainer.setVisibility(visibility);
        mainBinding.floatingVerticalDuoPill.setVisibility(visibility);
    }

    public PlayerSheetController getPlayerSheetController() {
        return playerSheetController;
    }

    /**
     * Wires up adaptive navigation: Portrait Grounded Dock + Landscape Floating Duo Pill.
     */
    private void setupGroundedDock() {
        adaptiveNavController = new AdaptiveGlassNavController(
                this,
                mainBinding.groundedDockContainer,
                mainBinding.groundedGlassDock,
                mainBinding.dockActiveIndicator,
                mainBinding.floatingVerticalDuoPill,
                mainBinding.duoActiveIndicator,
                mainBinding.duoTabHome,
                mainBinding.duoTabSubs,
                mainBinding.duoTabDownloads,
                mainBinding.duoTabLibrary,
                mainBinding.duoIconHome,
                mainBinding.duoIconSubs,
                mainBinding.duoIconDownloads,
                mainBinding.duoIconLibrary,
                mainBinding.duoTextHome,
                mainBinding.duoTextSubs,
                mainBinding.duoTextDownloads,
                mainBinding.duoTextLibrary
        );

        playerSheetController = new PlayerSheetController(
                this,
                mainBinding.fragmentPlayerHolder,
                mainBinding.groundedDockContainer,
                mainBinding.floatingVerticalDuoPill
        );

        playerSheetController.setOnStateChangeListener(state -> {
            if (adaptiveNavController != null) {
                if (state == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED) {
                    adaptiveNavController.setPlayerActive(true);
                } else if (state == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_HIDDEN) {
                    adaptiveNavController.setPlayerActive(false);
                } else if (state == com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED) {
                    final boolean isLandscape = getResources().getConfiguration().orientation
                            == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                    adaptiveNavController.setPlayerActive(isLandscape);
                }
            }
            updateFeedsPadding();
        });

        ViewCompat.setOnApplyWindowInsetsListener(mainBinding.groundedDockContainer, (v, insets) -> {
            final int bottom = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()).bottom;
            v.setPadding(0, 0, 0, bottom);
            mainBinding.groundedGlassDock.post(() -> {
                if (playerSheetController != null) {
                    playerSheetController.updatePeekHeight();
                }
                updateFeedsPadding();
            });
            return insets;
        });

        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            updateHeaderNavigationIcon();
            if (playerSheetController == null || !playerSheetController.isExpanded()) {
                if (getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                }
            }
            if (adaptiveNavController != null) {
                adaptiveNavController.syncWithActiveFragment();
            }
            updateFeedsPadding();
        });
    }

    /**
     * Sets up non-colliding header actions: Adaptive nav button, Search pill,
     * and Search Edit Mode with real focusable EditText.
     */
    private void setupHeaderActions() {
        setupBrandingTitle();

        // 1. Single Adaptive Left Navigation Button (Hamburger / Back)
        toolbarLayoutBinding.btnHeaderNav.setOnClickListener(v -> {
            final Fragment currentFragment = getSupportFragmentManager()
                    .findFragmentById(R.id.fragment_holder);
            if (isRootTabFragment(currentFragment)) {
                if (mainBinding.drawerLayout.isDrawerOpen(androidx.core.view.GravityCompat.START)) {
                    mainBinding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START);
                } else {
                    mainBinding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START);
                }
            } else {
                getOnBackPressedDispatcher().onBackPressed();
            }
        });


        // 4. Search Edit Mode Exit (Back button)
        toolbarLayoutBinding.btnSearchBack.setOnClickListener(v -> exitSearchMode(true));

        // 5. Search Clear Button
        toolbarLayoutBinding.btnSearchClear.setOnClickListener(v -> {
            toolbarLayoutBinding.searchEditText.setText("");
        });

        // 6. TextWatcher & IME Action on Search EditText
        toolbarLayoutBinding.searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {}

            @Override
            public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
                final boolean hasText = s != null && s.length() > 0;
                toolbarLayoutBinding.btnSearchClear.setVisibility(hasText ? View.VISIBLE : View.GONE);
            }

            @Override
            public void afterTextChanged(final Editable s) {}
        });

        toolbarLayoutBinding.searchEditText.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                final String query = toolbarLayoutBinding.searchEditText.getText().toString().trim();
                if (!TextUtils.isEmpty(query)) {
                    hideKeyboard();
                    NavigationHelper.openSearchFragment(
                            getSupportFragmentManager(),
                            ServiceHelper.getSelectedServiceId(this),
                            query
                    );
                }
                return true;
            }
            return false;
        });
    }

    private com.airbnb.lottie.LottieAnimationView startupAnimation;
    private final Runnable finishStartup = this::dismissStartupBranding;

    private void showStartupBranding() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                && !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            return;
        }
        final com.airbnb.lottie.LottieAnimationView animation =
                new com.airbnb.lottie.LottieAnimationView(this);
        startupAnimation = animation;
        animation.setBackgroundColor(0xFF090A0F);
        animation.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        final int padding = Math.max(0, (getResources().getDisplayMetrics().widthPixels
                - DeviceUtils.dpToPx(112, this)) / 2);
        animation.setPadding(padding, 0, padding, 0);
        animation.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        animation.setOnClickListener(v -> dismissStartupBranding());
        animation.setFailureListener(error -> dismissStartupBranding());
        animation.addAnimatorListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(final android.animation.Animator animator) {
                dismissStartupBranding();
            }
        });
        addContentView(animation, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        animation.setAnimation("frost_startup.json");
        animation.playAnimation();
        // Never hold navigation hostage to animation decoding or a lifecycle interruption.
        animation.postDelayed(finishStartup, 1200);
    }

    private void dismissStartupBranding() {
        final com.airbnb.lottie.LottieAnimationView animation = startupAnimation;
        startupAnimation = null;
        if (animation != null) {
            animation.removeCallbacks(finishStartup);
            animation.removeAllAnimatorListeners();
            animation.cancelAnimation();
            if (animation.getParent() instanceof ViewGroup) {
                ((ViewGroup) animation.getParent()).removeView(animation);
            }
        }
    }

    private void setupBrandingTitle() {
        final CharSequence styledTitle = getFrostTubeFormattedTitle();
        toolbarLayoutBinding.headerTitle.setText(styledTitle);

        try {
            final View drawerTitle = findViewById(R.id.drawer_header_newpipe_title);
            if (drawerTitle instanceof android.widget.TextView) {
                ((android.widget.TextView) drawerTitle).setText(styledTitle);
            }
        } catch (final Throwable ignored) {
        }
    }

    public static CharSequence getFrostTubeFormattedTitle() {
        final SpannableStringBuilder ssb = new SpannableStringBuilder();

        final SpannableString frost = new SpannableString("FROST");
        // Silver glassy metallic tint
        frost.setSpan(new ForegroundColorSpan(0xFFE2E8F0), 0, frost.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        frost.setSpan(new StyleSpan(Typeface.BOLD), 0, frost.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        final SpannableString tube = new SpannableString("TUBE");
        // Vibrant red
        tube.setSpan(new ForegroundColorSpan(0xFFFF284D), 0, tube.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        tube.setSpan(new StyleSpan(Typeface.BOLD), 0, tube.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        ssb.append(frost).append(tube);
        return ssb;
    }

    public void enterSearchMode(final String initialQuery) {
        toolbarLayoutBinding.headerNormalGroup.setVisibility(View.GONE);
        toolbarLayoutBinding.headerSearchEditGroup.setVisibility(View.VISIBLE);
        if (initialQuery != null && !initialQuery.equals(toolbarLayoutBinding.searchEditText.getText().toString())) {
            toolbarLayoutBinding.searchEditText.setText(initialQuery);
        }
        toolbarLayoutBinding.searchEditText.requestFocus();
        showKeyboard(toolbarLayoutBinding.searchEditText);
    }

    public void exitSearchMode(final boolean popFragment) {
        toolbarLayoutBinding.headerSearchEditGroup.setVisibility(View.GONE);
        toolbarLayoutBinding.headerNormalGroup.setVisibility(View.VISIBLE);
        toolbarLayoutBinding.headerTitle.setText(getFrostTubeFormattedTitle());
        toolbarLayoutBinding.searchEditText.clearFocus();
        hideKeyboard();

        if (popFragment) {
            final Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_holder);
            if (current instanceof SearchFragment) {
                getOnBackPressedDispatcher().onBackPressed();
            }
        }
    }

    private boolean isRootTabFragment(final Fragment fragment) {
        return fragment instanceof MainFragment
                || fragment instanceof SubscriptionFragment
                || fragment instanceof GlassDownloadFragment
                || fragment instanceof LibraryHubFragment;
    }

    private void updateHeaderNavigationIcon() {
        final Fragment fragment = getSupportFragmentManager().findFragmentById(R.id.fragment_holder);
        final boolean isRoot = isRootTabFragment(fragment);
        // Home uses the FrostTube mark as its leading affordance; keep the hamburger only
        // on secondary pages where it is actually a back/navigation control.
        toolbarLayoutBinding.btnHeaderNav.setVisibility(isRoot ? View.GONE : View.VISIBLE);

        if (isRoot) {
            toolbarLayoutBinding.btnHeaderNavIcon.setImageResource(R.drawable.ic_menu);
            toolbarLayoutBinding.btnHeaderNav.setContentDescription(getString(R.string.drawer_open));
        } else {
            toolbarLayoutBinding.btnHeaderNavIcon.setImageResource(R.drawable.ic_arrow_back);
            toolbarLayoutBinding.btnHeaderNav.setContentDescription(getString(R.string.back));
        }

        if (!(fragment instanceof SearchFragment)
                && toolbarLayoutBinding.headerSearchEditGroup.getVisibility() == View.VISIBLE) {
            exitSearchMode(false);
        }
    }

    private void showKeyboard(final View view) {
        if (view == null) return;
        view.post(() -> {
            final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void hideKeyboard() {
        final View currentFocus = getCurrentFocus();
        if (currentFocus != null) {
            final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(currentFocus.getWindowToken(), 0);
            }
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull final Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        final boolean isLand = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE;
        final boolean isPlayerExpanded = playerSheetController != null && playerSheetController.isExpanded();
        if (isLand && isPlayerExpanded) {
            mainBinding.floatingVerticalDuoPill.setVisibility(View.GONE);
            mainBinding.groundedDockContainer.setVisibility(View.GONE);
        } else if (!isPlayerExpanded) {
            if (getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
        }
        if (playerSheetController != null) {
            playerSheetController.onConfigurationChanged(newConfig);
        }
        if (adaptiveNavController != null) {
            adaptiveNavController.onConfigurationChanged(newConfig);
        }
        updateFeedsPadding();
    }

    @Override
    protected void onDestroy() {
        dismissStartupBranding();
        super.onDestroy();
        if (!isChangingConfigurations()) {
            StateSaver.clearStateFiles();
        }
        if (broadcastReceiver != null) {
            unregisterReceiver(broadcastReceiver);
        }
    }

    @Override
    protected void onResume() {
        Localization.initPrettyTime(Localization.resolvePrettyTime());
        super.onResume();

        if (playerSheetController == null || !playerSheetController.isExpanded()) {
            if (getRequestedOrientation() != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
        }
        updateFeedsPadding();

        if (sharedPreferences.getBoolean(Constants.KEY_THEME_CHANGE, false)) {
            if (DEBUG) {
                Log.d(TAG, "Theme has changed, recreating activity...");
            }
            sharedPrefEditor.putBoolean(Constants.KEY_THEME_CHANGE, false).apply();
            ActivityCompat.recreate(this);
        }

        if (sharedPreferences.getBoolean(Constants.KEY_MAIN_PAGE_CHANGE, false)) {
            if (DEBUG) {
                Log.d(TAG, "main page has changed, recreating main fragment...");
            }
            sharedPrefEditor.putBoolean(Constants.KEY_MAIN_PAGE_CHANGE, false).apply();
            NavigationHelper.openMainActivity(this);
        }
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        dismissStartupBranding();
        if (DEBUG) {
            Log.d(TAG, "onNewIntent() called with: intent = [" + intent + "]");
        }
        if (intent != null) {
            // Return if launched from a launcher (e.g. Nova Launcher, Pixel Launcher ...)
            // to not destroy the already created backstack
            final String action = intent.getAction();
            if ((action != null && action.equals(Intent.ACTION_MAIN))
                    && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
                return;
            }
        }

        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        final Fragment fragment = getSupportFragmentManager()
                .findFragmentById(R.id.fragment_player_holder);
        if (fragment instanceof OnKeyDownListener
                && !bottomSheetHiddenOrCollapsed()) {
            // Provide keyDown event to fragment which then sends this event
            // to the main player service
            return ((OnKeyDownListener) fragment).onKeyDown(keyCode)
                    || super.onKeyDown(keyCode, event);
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (DEBUG) {
            Log.d(TAG, "onBackPressed() called");
        }

        if (startupAnimation != null) {
            dismissStartupBranding();
            return;
        }
        // Back may interrupt an in-flight sheet expansion without restarting playback.
        if (playerSheetController != null
                && playerSheetController.getBehavior().getState() == BottomSheetBehavior.STATE_SETTLING) {
            playerSheetController.collapse();
            return;
        }

        // 1. Restore portrait orientation when navigating back from any landscape view
        if (org.schabi.newpipe.util.DeviceUtils.isLandscape(this)) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }

        // 2. Delegate to active video player first so it can exit fullscreen / handle internal stack
        final Fragment fragmentPlayer = getSupportFragmentManager()
                .findFragmentById(R.id.fragment_player_holder);
        if (fragmentPlayer instanceof BackPressable && !bottomSheetHiddenOrCollapsed()) {
            if (((BackPressable) fragmentPlayer).onBackPressed()) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                return;
            }
        }

        // 3. If player is expanded, collapse it to compact mini-player card above dock
        if (playerSheetController != null && playerSheetController.isExpanded()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            playerSheetController.collapse();
            return;
        }

        // 4. In case bottomSheet is not visible on the screen or collapsed we can assume that the user
        // interacts with a fragment inside fragment_holder so all back presses should be
        // handled by it
        if (bottomSheetHiddenOrCollapsed()) {
            final FragmentManager fm = getSupportFragmentManager();
            final Fragment fragment = fm.findFragmentById(R.id.fragment_holder);
            // If current fragment implements BackPressable (i.e. can/wanna handle back press)
            // delegate the back press to it
            if (fragment instanceof BackPressable) {
                if (((BackPressable) fragment).onBackPressed()) {
                    return;
                }
            } else if (fragment instanceof CommentRepliesFragment) {
                // expand DetailsFragment if CommentRepliesFragment was opened
                // to show the top level comments again
                // Expand DetailsFragment if CommentRepliesFragment was opened
                // and no other CommentRepliesFragments are on top of the back stack
                // to show the top level comments again.
                openDetailFragmentFromCommentReplies(fm, false);
                return;
            }

        } else {
            // If current fragment implements BackPressable (i.e. can/wanna handle back press)
            // delegate the back press to it
            if (fragmentPlayer instanceof BackPressable) {
                if (!((BackPressable) fragmentPlayer).onBackPressed()) {
                    BottomSheetBehavior.from(mainBinding.fragmentPlayerHolder)
                            .setState(BottomSheetBehavior.STATE_COLLAPSED);
                }
                return;
            }
        }

        if (getSupportFragmentManager().getBackStackEntryCount() <= 1) {
            finish();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(final int requestCode,
                                           @NonNull final String[] permissions,
                                           @NonNull final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        for (final int i : grantResults) {
            if (i == PackageManager.PERMISSION_DENIED) {
                return;
            }
        }
        switch (requestCode) {
            case PermissionHelper.DOWNLOADS_REQUEST_CODE:
                NavigationHelper.openDownloads(this);
                break;
            case PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE:
                final Fragment fragment = getSupportFragmentManager()
                        .findFragmentById(R.id.fragment_player_holder);
                if (fragment instanceof VideoDetailFragment) {
                    ((VideoDetailFragment) fragment).openDownloadDialog();
                }
                break;
            case PermissionHelper.POST_NOTIFICATIONS_REQUEST_CODE:
                NotificationWorker.initialize(this);
                break;
        }
    }

    /**
     * Implement the following diagram behavior for the up button:
     * <pre>
     *              +---------------+
     *              |  Main Screen  +----+
     *              +-------+-------+    |
     *                      |            |
     *                      ▲ Up         | Search Button
     *                      |            |
     *                 +----+-----+      |
     *    +------------+  Search  |◄-----+
     *    |            +----+-----+
     *    |   Open          |
     *    |  something      ▲ Up
     *    |                 |
     *    |    +------------+-------------+
     *    |    |                          |
     *    |    |  Video    <->  Channel   |
     *    +---►|  Channel  <->  Playlist  |
     *         |  Video    <->  ....      |
     *         |                          |
     *         +--------------------------+
     * </pre>
     */
    private void onHomeButtonPressed() {
        final FragmentManager fm = getSupportFragmentManager();
        final Fragment fragment = fm.findFragmentById(R.id.fragment_holder);

        if (fragment instanceof CommentRepliesFragment) {
            // Expand DetailsFragment if CommentRepliesFragment was opened
            // and no other CommentRepliesFragments are on top of the back stack
            // to show the top level comments again.
            openDetailFragmentFromCommentReplies(fm, true);
        } else if (!NavigationHelper.tryGotoSearchFragment(fm)) {
            // If search fragment wasn't found in the backstack go to the main fragment
            NavigationHelper.gotoMainFragment(fm);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        if (DEBUG) {
            Log.d(TAG, "onCreateOptionsMenu() called with: menu = [" + menu + "]");
        }
        super.onCreateOptionsMenu(menu);

        final Fragment fragment =
                getSupportFragmentManager().findFragmentById(R.id.fragment_holder);
        if (!(fragment instanceof SearchFragment)) {
            toolbarLayoutBinding.headerSearchEditGroup.setVisibility(View.GONE);
            toolbarLayoutBinding.headerNormalGroup.setVisibility(View.VISIBLE);
        }

        final ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(false);
        }

        updateHeaderNavigationIcon();

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        if (DEBUG) {
            Log.d(TAG, "onOptionsItemSelected() called with: item = [" + item + "]");
        }

        if (item.getItemId() == android.R.id.home) {
            onHomeButtonPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    //////////////////////////////////////////////////////////////////////////*/

    private void initFragments() {
        if (DEBUG) {
            Log.d(TAG, "initFragments() called");
        }
        StateSaver.clearStateFiles();
        if (getIntent() != null && getIntent().hasExtra(Constants.KEY_LINK_TYPE)) {
            // When user watch a video inside popup and then tries to open the video in main player
            // while the app is closed he will see a blank fragment on place of kiosk.
            // Let's open it first
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                NavigationHelper.openMainFragment(getSupportFragmentManager());
            }

            handleIntent(getIntent());
        } else {
            NavigationHelper.gotoMainFragment(getSupportFragmentManager());
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Utils
    //////////////////////////////////////////////////////////////////////////*/

    private void updateDrawerNavigation() {
        updateHeaderNavigationIcon();
    }

    private void handleIntent(final Intent intent) {
        try {
            if (DEBUG) {
                Log.d(TAG, "handleIntent() called with: intent = [" + intent + "]");
            }

            if (intent.hasExtra(Constants.KEY_LINK_TYPE)) {
                final String url = intent.getStringExtra(Constants.KEY_URL);
                final int serviceId = intent.getIntExtra(Constants.KEY_SERVICE_ID, 0);
                String title = intent.getStringExtra(Constants.KEY_TITLE);
                if (title == null) {
                    title = "";
                }

                final StreamingService.LinkType linkType = ((StreamingService.LinkType) intent
                        .getSerializableExtra(Constants.KEY_LINK_TYPE));
                assert linkType != null;
                switch (linkType) {
                    case STREAM:
                        final String intentCacheKey = intent.getStringExtra(
                                Player.PLAY_QUEUE_KEY);
                        final PlayQueue playQueue = intentCacheKey != null
                                ? SerializedCache.getInstance()
                                .take(intentCacheKey, PlayQueue.class)
                                : null;

                        final boolean switchingPlayers = intent.getBooleanExtra(
                                VideoDetailFragment.KEY_SWITCHING_PLAYERS, false);
                        NavigationHelper.openVideoDetailFragment(
                                getApplicationContext(), getSupportFragmentManager(),
                                serviceId, url, title, playQueue, switchingPlayers);
                        break;
                    case CHANNEL:
                        NavigationHelper.openChannelFragment(getSupportFragmentManager(),
                                serviceId, url, title);
                        break;
                    case PLAYLIST:
                        NavigationHelper.openPlaylistFragment(getSupportFragmentManager(),
                                serviceId, url, title);
                        break;
                }
            } else if (intent.hasExtra(Constants.KEY_OPEN_SEARCH)) {
                String searchString = intent.getStringExtra(Constants.KEY_SEARCH_STRING);
                if (searchString == null) {
                    searchString = "";
                }
                final int serviceId = intent.getIntExtra(Constants.KEY_SERVICE_ID, 0);
                NavigationHelper.openSearchFragment(
                        getSupportFragmentManager(),
                        serviceId,
                        searchString);

            } else {
                NavigationHelper.gotoMainFragment(getSupportFragmentManager());
            }
        } catch (final Exception e) {
            ErrorUtil.showUiErrorSnackbar(this, "Handling intent", e);
        }
    }

    private void openMiniPlayerIfMissing() {
        final Fragment fragmentPlayer = getSupportFragmentManager()
                .findFragmentById(R.id.fragment_player_holder);
        if (fragmentPlayer == null) {
            // We still don't have a fragment attached to the activity. It can happen when a user
            // started popup or background players without opening a stream inside the fragment.
            // Adding it in a collapsed state (only mini player will be visible).
            NavigationHelper.showMiniPlayer(getSupportFragmentManager());
        }
    }

    private void openMiniPlayerUponPlayerStarted() {
        if (getIntent().getSerializableExtra(Constants.KEY_LINK_TYPE)
                == StreamingService.LinkType.STREAM) {
            // handleIntent() already takes care of opening video detail fragment
            // due to an intent containing a STREAM link
            return;
        }

        if (PlayerHolder.getInstance().isPlayerOpen()) {
            // if the player is already open, no need for a broadcast receiver
            openMiniPlayerIfMissing();
        } else {
            // listen for player start intent being sent around
            broadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(final Context context, final Intent intent) {
                    if (Objects.equals(intent.getAction(),
                            VideoDetailFragment.ACTION_PLAYER_STARTED)
                            && PlayerHolder.getInstance().isPlayerOpen()) {
                        openMiniPlayerIfMissing();
                        // At this point the player is added 100%, we can unregister. Other actions
                        // are useless since the fragment will not be removed after that.
                        unregisterReceiver(broadcastReceiver);
                        broadcastReceiver = null;
                    }
                }
            };
            final IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(VideoDetailFragment.ACTION_PLAYER_STARTED);
            ContextCompat.registerReceiver(this, broadcastReceiver, intentFilter,
                    ContextCompat.RECEIVER_EXPORTED);

            // If the PlayerHolder is not bound yet, but the service is running, try to bind to it.
            // Once the connection is established, the ACTION_PLAYER_STARTED will be sent.
            PlayerHolder.getInstance().tryBindIfNeeded(this);
        }
    }

    private void openDetailFragmentFromCommentReplies(
            @NonNull final FragmentManager fm,
            final boolean popBackStack
    ) {
        // obtain the name of the fragment under the replies fragment that's going to be popped
        @Nullable final String fragmentUnderEntryName;
        if (fm.getBackStackEntryCount() < 2) {
            fragmentUnderEntryName = null;
        } else {
            fragmentUnderEntryName = fm.getBackStackEntryAt(fm.getBackStackEntryCount() - 2)
                    .getName();
        }

        // the root comment is the comment for which the user opened the replies page
        @Nullable final CommentRepliesFragment repliesFragment =
                (CommentRepliesFragment) fm.findFragmentByTag(CommentRepliesFragment.TAG);
        @Nullable final CommentsInfoItem rootComment =
                repliesFragment == null ? null : repliesFragment.getCommentsInfoItem();

        // sometimes this function pops the backstack, other times it's handled by the system
        if (popBackStack) {
            fm.popBackStackImmediate();
        }

        // only expand the bottom sheet back if there are no more nested comment replies fragments
        // stacked under the one that is currently being popped
        if (CommentRepliesFragment.TAG.equals(fragmentUnderEntryName)) {
            return;
        }

        final BottomSheetBehavior<FragmentContainerView> behavior = BottomSheetBehavior
                .from(mainBinding.fragmentPlayerHolder);
        // do not return to the comment if the details fragment was closed
        if (behavior.getState() == BottomSheetBehavior.STATE_HIDDEN) {
            return;
        }

        // scroll to the root comment once the bottom sheet expansion animation is finished
        behavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull final View bottomSheet,
                                       final int newState) {
                if (newState == BottomSheetBehavior.STATE_EXPANDED) {
                    final Fragment detailFragment = fm.findFragmentById(
                            R.id.fragment_player_holder);
                    if (detailFragment instanceof VideoDetailFragment && rootComment != null) {
                        // should always be the case
                        ((VideoDetailFragment) detailFragment).scrollToComment(rootComment);
                    }
                    behavior.removeBottomSheetCallback(this);
                }
            }

            @Override
            public void onSlide(@NonNull final View bottomSheet, final float slideOffset) {
                // not needed, listener is removed once the sheet is expanded
            }
        });

        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
    }

    private boolean bottomSheetHiddenOrCollapsed() {
        final BottomSheetBehavior<FrameLayout> bottomSheetBehavior =
                BottomSheetBehavior.from(mainBinding.fragmentPlayerHolder);

        final int sheetState = bottomSheetBehavior.getState();
        return sheetState == BottomSheetBehavior.STATE_HIDDEN
                || sheetState == BottomSheetBehavior.STATE_COLLAPSED;
    }

    private void showKeepAndroidDialog() {
        final var prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final var lastCheckKey = getString(R.string.kao_last_checked_key);
        final var lastCheck = Instant.ofEpochMilli(prefs.getLong(lastCheckKey, 0));
        final var now = Instant.now();

        if (lastCheck.plus(30, ChronoUnit.DAYS).isBefore(now)) {
            final String detailsUrl = getKeepAndroidOpenDetailsUrl();
            final var solutionUrl = "https://github.com/woheller69/FreeDroidWarn#solutions";

            final var dialog = new AlertDialog.Builder(this)
                    .setTitle("Keep Android Open")
                    .setCancelable(false)
                    .setMessage(R.string.kao_dialog_warning)
                    .setPositiveButton(android.R.string.ok, (d, w) -> prefs.edit()
                            .putLong(lastCheckKey, now.toEpochMilli())
                            .apply())
                    .setNeutralButton(R.string.kao_solution, null)
                    .setNegativeButton(R.string.kao_dialog_more_info, null)
                    .show();

            // If we use setNeutralButton/setNegativeButton, dialog will close after pressing the
            // buttons, but we want it to close only when positive button is pressed
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setOnClickListener(v -> ShareUtils.openUrlInBrowser(this, detailsUrl));
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setOnClickListener(v -> ShareUtils.openUrlInBrowser(this, solutionUrl));
        }
    }

    @NonNull
    private static String getKeepAndroidOpenDetailsUrl() {
        final var supportedLanguages = List.of("fr", "de", "ca", "es", "id", "it", "pl",
                "pt", "cs", "sk", "fa", "ar", "tr", "el", "th", "ru", "uk", "ko", "zh", "ja");
        final String kaoBaseUrl = "https://keepandroidopen.org/";
        final var locale = Localization.getAppLocale();
        if (supportedLanguages.contains(locale.getLanguage())) {
            if ("zh".equals(locale.getLanguage())) {
                return kaoBaseUrl + ("TW".equals(locale.getCountry()) ? "zh-TW" : "zh-CN");
            } else {
                return kaoBaseUrl + locale.getLanguage();
            }
        } else {
            return kaoBaseUrl;
        }
    }

    private void showApi23RequirementDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return; // only show dialog on the devices that will stop being supported
        }

        final var prefs = PreferenceManager.getDefaultSharedPreferences(this);
        final var shownKey = getString(R.string.api23_requirement_dialog_shown_key);
        if (prefs.getBoolean(shownKey, false)) {
            return; // dialog was already shown in the past, no need to show it again
        }

        final var dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.api23_requirement_dialog_title)
                .setCancelable(false)
                .setMessage(R.string.api23_requirement_dialog_message)
                .setPositiveButton(android.R.string.ok, (d, w) -> prefs.edit()
                        .putBoolean(shownKey, true)
                        .apply())
                .setNegativeButton(R.string.api23_requirement_dialog_blogpost, null)
                .show();

        // If we use setNegativeButton, dialog will close after pressing the button,
        // but we want it to close only when positive button is pressed
        final var blogpostUrl = "https://newpipe.net/blog/pinned/announcement/drop-android-5/";
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setOnClickListener(v -> ShareUtils.openUrlInBrowser(this, blogpostUrl));
    }
}
