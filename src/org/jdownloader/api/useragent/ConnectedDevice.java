package org.jdownloader.api.useragent;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.appwork.remoteapi.RemoteAPIRequest;
import org.appwork.utils.StringUtils;
import org.jdownloader.api.myjdownloader.MyJDownloaderDirectHttpConnection;
import org.jdownloader.api.myjdownloader.MyJDownloaderHttpConnection;

public class ConnectedDevice {
    public static final String   FRONTEND_WEBINTERFACE              = "Webinterface http://my.jdownloader.org by AppWork";
    public static final String   FRONTEND_ANDROID_APP               = "MyJDownloader by AppWork";
    public static final String   FRONTEND_FIREFOX_EXTENSION         = "Firefox Extension by AppWork";
    public static final String   FRONTEND_CHROME_EXTENSION          = "Chrome Extension by AppWork";
    private static final Pattern ANDROID_USERAGENT_PATTERN          = Pattern.compile("MyJDownloader Android App \\(Version: (.+) \\/ (.+)\\) \\(Android (.+);(.+)\\/(.+)\\)");
    private static final Pattern WINMOB_FILERECON_USERAGENT_PATTERN = Pattern.compile("MyJDownloader file.recon App \\(Version: (.+) \\/ (.+)\\)");
    private static final Pattern WIN_UNIVERSAL_USERAGENT_PATTERN    = Pattern.compile("MyJDownloader JD Universal App \\(Version: (.+) \\/ (.+)\\)");
    private RemoteAPIRequest     latestRequest;
    private String               token;

    public String getConnectToken() {
        return token;
    }

    public void setConnectToken(String token) {
        this.token = token;
    }

    private long          lastPing;
    private UserAgentInfo info;

    public UserAgentInfo getInfo() {
        return info;
    }

    private String id;

    public String getId() {
        return id;
    }

    public long getLastPing() {
        return lastPing;
    }

    public ConnectedDevice(String nuaID) {
        this.id = nuaID;
    }

    public long getTimeout() {
        return 5 * 60 * 1000l;
    }

    private String deviceName = null;
    private String frontEnd   = null;

    public void setLatestRequest(RemoteAPIRequest request) {
        latestRequest = request;
        this.lastPing = System.currentTimeMillis();
        if (latestRequest.getRequestedPath().contains("anywhere")) {
            // workaround. jdanywhere does not use a user agent
            deviceName = "IPhone/IPad";
            frontEnd = "JDAnywhere";
        } else {
            deviceName = _getDeviceName();
            frontEnd = _getFrontendName();
        }
    }

    private String _getFrontendName() {
        final String origin = latestRequest.getRequestHeaders().getValue("Origin");
        final String referer = latestRequest.getRequestHeaders().getValue("Referer");
        if (StringUtils.startsWithCaseInsensitive(origin, "http://my.jdownloader.org") || StringUtils.startsWithCaseInsensitive(origin, "https://my.jdownloader.org")) {
            return FRONTEND_WEBINTERFACE;
        } else if (StringUtils.startsWithCaseInsensitive(referer, "http://my.jdownloader.org") || StringUtils.startsWithCaseInsensitive(referer, "https://my.jdownloader.org")) {
            return FRONTEND_WEBINTERFACE;
        } else if (StringUtils.startsWithCaseInsensitive(origin, "chrome-extension://")) {
            return FRONTEND_CHROME_EXTENSION;
        } else if (StringUtils.equals(origin, "null")) {
            // TODO: add proper detection
            return FRONTEND_FIREFOX_EXTENSION;
        } else if (isAndroidApp(getUserAgentString())) {
            return FRONTEND_ANDROID_APP;
        } else if (isFileReconApp(getUserAgentString())) {
            return "file.recon by Pseudocode";
        } else if (isJDUniversalApp(getUserAgentString())) {
            return "JD Universal by Pseudocode";
        }
        if (StringUtils.isNotEmpty(origin) && !"null".equals(origin)) {
            return origin;
        }
        return "Unknown";
    }

    private String _getDeviceName() {
        final String uA = getUserAgentString();
        if (isAndroidApp(uA)) {
            final Matcher matcher = ANDROID_USERAGENT_PATTERN.matcher(uA);
            if (matcher.matches()) {
                return matcher.group(4).replace(" ", "") + matcher.group(5).replace(" ", "") + "@Android" + matcher.group(3);
            } else {
                return "Android";
            }
        } else if (isFileReconApp(uA)) {
            final Matcher matcher = WINMOB_FILERECON_USERAGENT_PATTERN.matcher(uA);
            if (matcher.matches()) {
                return "file.recon" + matcher.group(1).replace(" ", "") + "(" + matcher.group(2).replace(" ", "") + ")" + "@WindowsMobile";
            } else {
                return "file.recon@WindowsMobile";
            }
        } else if (isJDUniversalApp(uA)) {
            final Matcher matcher = WIN_UNIVERSAL_USERAGENT_PATTERN.matcher(uA);
            if (matcher.matches()) {
                return "JDUniversal" + matcher.group(1).replace(" ", "") + "(" + matcher.group(2).replace(" ", "") + ")" + "@Win10";
            } else {
                return "JDUniversal@Win10";
            }
        }
        if (info != null) {
            return info.getName() + "@" + info.getOs();
        } else {
            return getUserAgentString();
        }
    }

    public String getDeviceName() {
        return deviceName;
    }

    public String getUserAgentString() {
        return latestRequest.getRequestHeaders().getValue("User-Agent");
    }

    public void setInfo(UserAgentInfo info) {
        this.info = info;
    }

    public String getFrontendName() {
        return frontEnd;
    }

    public static boolean isAndroidApp(final String userAgent) {
        return StringUtils.startsWithCaseInsensitive(userAgent, "MyJDownloader Android App");
    }

    public static boolean isFileReconApp(final String userAgent) {
        return StringUtils.startsWithCaseInsensitive(userAgent, "MyJDownloader file.recon App");
    }

    public static boolean isJDUniversalApp(final String userAgent) {
        return StringUtils.startsWithCaseInsensitive(userAgent, "MyJDownloader JD Universal App");
    }

    public static boolean isApp(final String userAgent) {
        return isAndroidApp(userAgent) || isFileReconApp(userAgent) || isJDUniversalApp(userAgent);
    }

    public String getConnectionString() {
        final List<MyJDownloaderHttpConnection> list = MyJDownloaderHttpConnection.getConnectionsByToken(getConnectToken());
        if (list == null || list.size() == 0) {
            return "0 Connections";
        } else {
            int remote = 0;
            int direct = 0;
            for (MyJDownloaderHttpConnection con : list) {
                if (con instanceof MyJDownloaderDirectHttpConnection) {
                    direct++;
                } else {
                    remote++;
                }
            }
            if (direct > 0 && remote > 0) {
                return direct + " Direct Connection(s) and " + remote + " Remote Connection(s)";
            } else if (direct > 0) {
                return direct + " Direct Connection(s)";
            } else if (remote > 0) {
                return remote + " Remote Connection(s)";
            } else {
                return "0 Connections";
            }
        }
    }

    public RemoteAPIRequest getLatestRequest() {
        return latestRequest;
    }
}
