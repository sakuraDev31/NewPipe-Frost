package org.schabi.newpipe.util;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.util.Log;
import android.util.Range;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.schabi.newpipe.extractor.MediaFormat;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper to query MediaCodecList / VideoCapabilities for maximum supported video decoding
 * resolution and frame rates across AVC, VP9, and AV1 codecs.
 */
public final class DeviceCapabilityHelper {
    private static final String TAG = "DeviceCapabilityHelper";

    public static final String MIME_AVC = "video/avc";
    public static final String MIME_VP9 = "video/x-vnd.on2.vp9";
    public static final String MIME_AV1 = "video/av01";
    public static final String MIME_3GPP = "video/3gpp";

    private static final Pattern RESOLUTION_PATTERN =
            Pattern.compile("(\\d+)p(\\d+)?", Pattern.CASE_INSENSITIVE);

    private static final Map<String, CodecProfileLimits> CACHED_LIMITS = new ConcurrentHashMap<>();
    private static volatile boolean initialized = false;

    private DeviceCapabilityHelper() { }

    public static class CodecProfileLimits {
        public final int maxWidth;
        public final int maxHeight;
        public final int maxFrameRate;
        public final boolean isSupported;

        public CodecProfileLimits(final int maxWidth, final int maxHeight,
                                  final int maxFrameRate, final boolean isSupported) {
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            this.maxFrameRate = maxFrameRate;
            this.isSupported = isSupported;
        }

        @NonNull
        @Override
        public String toString() {
            return "maxWidth=" + maxWidth + ", maxHeight=" + maxHeight
                    + ", maxFps=" + maxFrameRate + ", supported=" + isSupported;
        }
    }

    /**
     * Initializes codec capabilities cache asynchronously or on-demand.
     */
    public static synchronized void init() {
        if (initialized) {
            return;
        }
        try {
            queryCodecLimits(MIME_AVC);
            queryCodecLimits(MIME_VP9);
            queryCodecLimits(MIME_AV1);
            initialized = true;
        } catch (final Throwable e) {
            Log.e(TAG, "Failed to inspect MediaCodecList", e);
        }
    }

    @NonNull
    public static CodecProfileLimits getLimitsForMime(@NonNull final String mimeType) {
        if (!initialized) {
            init();
        }
        final CodecProfileLimits limits = CACHED_LIMITS.get(mimeType.toLowerCase());
        if (limits != null) {
            return limits;
        }
        return queryCodecLimits(mimeType);
    }

    private static synchronized CodecProfileLimits queryCodecLimits(@NonNull final String mimeType) {
        int maxWidth = 0;
        int maxHeight = 0;
        int maxFps = 0;
        boolean found = false;

        try {
            final MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (final MediaCodecInfo info : codecList.getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (final String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mimeType)) {
                        found = true;
                        final MediaCodecInfo.CodecCapabilities caps =
                                info.getCapabilitiesForType(type);
                        if (caps != null && caps.getVideoCapabilities() != null) {
                            final MediaCodecInfo.VideoCapabilities vc =
                                    caps.getVideoCapabilities();
                            final Range<Integer> widths = vc.getSupportedWidths();
                            final Range<Integer> heights = vc.getSupportedHeights();
                            final Range<Integer> frameRates = vc.getSupportedFrameRates();

                            if (widths != null && widths.getUpper() > maxWidth) {
                                maxWidth = widths.getUpper();
                            }
                            if (heights != null && heights.getUpper() > maxHeight) {
                                maxHeight = heights.getUpper();
                            }
                            if (frameRates != null && frameRates.getUpper() > maxFps) {
                                maxFps = frameRates.getUpper();
                            }
                        }
                    }
                }
            }
        } catch (final Throwable t) {
            Log.w(TAG, "Error querying MediaCodecList for " + mimeType, t);
        }

        // Safe fallback defaults if no explicit VideoCapabilities could be read
        if (!found) {
            if (MIME_AVC.equalsIgnoreCase(mimeType)) {
                // Almost all Android devices support at least 1080p30 AVC
                maxWidth = 1920;
                maxHeight = 1080;
                maxFps = 30;
                found = true;
            } else {
                maxWidth = 0;
                maxHeight = 0;
                maxFps = 0;
            }
        } else if (maxWidth == 0 || maxHeight == 0) {
            maxWidth = 1920;
            maxHeight = 1080;
            maxFps = 60;
        }

        final CodecProfileLimits limits =
                new CodecProfileLimits(maxWidth, maxHeight, maxFps, found);
        CACHED_LIMITS.put(mimeType.toLowerCase(), limits);
        Log.d(TAG, "Codec capabilities for " + mimeType + ": " + limits);
        return limits;
    }

    /**
     * Checks if a particular resolution and frame rate can be decoded for the given mime type.
     */
    public static boolean isResolutionAndRateSupported(@NonNull final String mimeType,
                                                       final int width,
                                                       final int height,
                                                       final double frameRate) {
        try {
            final MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (final MediaCodecInfo info : codecList.getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (final String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mimeType)) {
                        final MediaCodecInfo.CodecCapabilities caps =
                                info.getCapabilitiesForType(type);
                        if (caps != null && caps.getVideoCapabilities() != null) {
                            final MediaCodecInfo.VideoCapabilities vc =
                                    caps.getVideoCapabilities();
                            if (vc.isSizeSupported(width, height)) {
                                if (vc.areSizeAndRateSupported(width, height, frameRate)) {
                                    return true;
                                }
                                final Range<Double> rates =
                                        vc.getSupportedFrameRatesFor(width, height);
                                if (rates != null && rates.contains(frameRate)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        } catch (final Throwable t) {
            Log.w(TAG, "Error evaluating isResolutionAndRateSupported", t);
        }

        // Fallback to max bounds check
        final CodecProfileLimits limits = getLimitsForMime(mimeType);
        if (!limits.isSupported) {
            return false;
        }
        return width <= limits.maxWidth
                && height <= limits.maxHeight
                && (frameRate <= limits.maxFrameRate || limits.maxFrameRate == 0);
    }

    /**
     * Extracts parsed height from resolution string (e.g., "1080p60" -> 1080).
     */
    public static int parseHeight(@Nullable final String resolution) {
        if (resolution == null) {
            return 0;
        }
        final Matcher matcher = RESOLUTION_PATTERN.matcher(resolution);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (final NumberFormatException ignored) {
            }
        }
        return 0;
    }

    /**
     * Extracts parsed frame rate from resolution string (e.g., "1080p60" -> 60, "1080p" -> 30).
     */
    public static int parseFrameRate(@Nullable final String resolution) {
        if (resolution == null) {
            return 30;
        }
        final Matcher matcher = RESOLUTION_PATTERN.matcher(resolution);
        if (matcher.find()) {
            final String fpsGroup = matcher.group(2);
            if (fpsGroup != null && !fpsGroup.isEmpty()) {
                try {
                    return Integer.parseInt(fpsGroup);
                } catch (final NumberFormatException ignored) {
                }
            }
        }
        return 30;
    }

    /**
     * Derives 16:9 width based on height.
     */
    public static int parseWidth(final int height) {
        return (int) Math.round((height * 16.0) / 9.0);
    }

    /**
     * Determines whether the given VideoStream is decodable by the device's hardware/media codecs.
     */
    public static boolean isStreamSupported(@NonNull final VideoStream stream) {
        final String resolution = stream.getResolution();
        final int height = parseHeight(resolution);
        if (height <= 0) {
            return true;
        }
        final int width = parseWidth(height);
        final int fps = parseFrameRate(resolution);

        final MediaFormat format = stream.getFormat();
        final List<String> candidateMimes = new ArrayList<>();

        if (format == MediaFormat.MPEG_4) {
            candidateMimes.add(MIME_AVC);
        } else if (format == MediaFormat.WEBM) {
            candidateMimes.add(MIME_VP9);
            candidateMimes.add(MIME_AV1);
        } else if (format == MediaFormat.v3GPP) {
            candidateMimes.add(MIME_3GPP);
        } else {
            candidateMimes.add(MIME_AVC);
            candidateMimes.add(MIME_VP9);
        }

        for (final String mime : candidateMimes) {
            if (isResolutionAndRateSupported(mime, width, height, fps)) {
                return true;
            }
        }

        // Baseline fallback: if device codec query fails or returns false for standard 1080p/720p,
        // permit standard HD streams on modern Android 8+ devices.
        if (height <= 1080 && fps <= 60 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return true;
        }

        return false;
    }

    /**
     * Filters a list of VideoStreams to only those decodable by the current device.
     */
    @NonNull
    public static List<VideoStream> filterSupportedStreams(
            @Nullable final List<VideoStream> streams) {
        if (streams == null || streams.isEmpty()) {
            return Collections.emptyList();
        }
        final List<VideoStream> supported = new ArrayList<>(streams.size());
        for (final VideoStream stream : streams) {
            if (isStreamSupported(stream)) {
                supported.add(stream);
            }
        }
        return supported.isEmpty() ? streams : supported;
    }

    /**
     * Returns the index of the best supported stream in the sorted list.
     */
    public static int getBestSupportedStreamIndex(@Nullable final List<VideoStream> sortedStreams) {
        if (sortedStreams == null || sortedStreams.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < sortedStreams.size(); i++) {
            if (isStreamSupported(sortedStreams.get(i))) {
                return i;
            }
        }
        return 0;
    }
}
