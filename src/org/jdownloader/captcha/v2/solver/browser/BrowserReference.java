package org.jdownloader.captcha.v2.solver.browser;

import java.awt.Rectangle;
import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import jd.controlling.captcha.SkipRequest;
import jd.parser.Regex;
import jd.plugins.Plugin;

import org.appwork.controlling.SingleReachableState;
import org.appwork.exceptions.WTFException;
import org.appwork.net.protocol.http.HTTPConstants;
import org.appwork.net.protocol.http.HTTPConstants.ResponseCode;
import org.appwork.remoteapi.exceptions.BasicRemoteAPIException;
import org.appwork.scheduler.DelayedRunnable;
import org.appwork.utils.Exceptions;
import org.appwork.utils.Files;
import org.appwork.utils.IO;
import org.appwork.utils.StringUtils;
import org.appwork.utils.event.queue.Queue;
import org.appwork.utils.event.queue.QueueAction;
import org.appwork.utils.logging2.LogInterface;
import org.appwork.utils.net.HTTPHeader;
import org.appwork.utils.net.URLHelper;
import org.appwork.utils.net.httpserver.HttpConnection.ConnectionHook;
import org.appwork.utils.net.httpserver.HttpHandlerInfo;
import org.appwork.utils.net.httpserver.handler.ExtendedHttpRequestHandler;
import org.appwork.utils.net.httpserver.handler.HttpRequestHandler;
import org.appwork.utils.net.httpserver.requests.GetRequest;
import org.appwork.utils.net.httpserver.requests.HttpRequest;
import org.appwork.utils.net.httpserver.requests.PostRequest;
import org.appwork.utils.net.httpserver.responses.HttpResponse;
import org.appwork.utils.os.CrossSystem;
import org.appwork.utils.processes.ProcessBuilderFactory;
import org.jdownloader.api.DeprecatedAPIHttpServerController;
import org.jdownloader.captcha.v2.Challenge;
import org.jdownloader.captcha.v2.ChallengeResponseController;
import org.jdownloader.captcha.v2.solver.service.BrowserSolverService;
import org.jdownloader.captcha.v2.solverjob.SolverJob;
import org.jdownloader.controlling.UniqueAlltimeID;
import org.jdownloader.logging.LogController;
import org.jdownloader.settings.staticreferences.CFG_GENERAL;

public abstract class BrowserReference implements ExtendedHttpRequestHandler, HttpRequestHandler, ConnectionHook {
    private final AbstractBrowserChallenge challenge;
    private final UniqueAlltimeID          id = new UniqueAlltimeID();

    public UniqueAlltimeID getId() {
        return id;
    }

    private final HashMap<String, URL>    resourceIds;
    private final HashMap<String, String> types;
    protected String                      base;
    {
        resourceIds = new HashMap<String, URL>();
        resourceIds.put("style.css", BrowserReference.class.getResource("html/style.css"));
        resourceIds.put("plax-1.png", BrowserReference.class.getResource("html/plax-1.png"));
        resourceIds.put("plax-2.png", BrowserReference.class.getResource("html/plax-2.png"));
        resourceIds.put("plax-3.png", BrowserReference.class.getResource("html/plax-3.png"));
        resourceIds.put("plax-4.png", BrowserReference.class.getResource("html/plax-4.png"));
        resourceIds.put("plax-5.png", BrowserReference.class.getResource("html/plax-5.png"));
        resourceIds.put("plax-6.png", BrowserReference.class.getResource("html/plax-6.png"));
        resourceIds.put("script.min.js", BrowserReference.class.getResource("html/script.min.js"));
        resourceIds.put("teaser.png", BrowserReference.class.getResource("html/teaser.png"));
        resourceIds.put("body-bg.jpg", BrowserReference.class.getResource("html/body-bg.jpg"));
        resourceIds.put("header-bg.jpg", BrowserReference.class.getResource("html/header-bg.jpg"));
        resourceIds.put("logo.png", BrowserReference.class.getResource("html/logo.png"));
        resourceIds.put("mediumblue-bg.jpg", BrowserReference.class.getResource("html/mediumblue-bg.jpg"));
        resourceIds.put("social.png", BrowserReference.class.getResource("html/social.png"));
        resourceIds.put("twitterbird.png", BrowserReference.class.getResource("html/twitterbird.png"));
        resourceIds.put("fuuuu.png", BrowserReference.class.getResource("html/fuuuu.png"));
        resourceIds.put("favicon.ico", BrowserReference.class.getResource("html/favicon.ico"));
        resourceIds.put("browserCaptcha.js", BrowserReference.class.getResource("html/browserCaptcha.js"));
        resourceIds.put("jquery-1.9.1-min.js", BrowserReference.class.getResource("html/jquery-1.9.1-min.js"));
        types = new HashMap<String, String>();
        types.put("html", "text/html; charset=utf-8");
        types.put("css", "text/css; charset=utf-8");
        types.put("png", "image/png");
        types.put("js", "text/javascript; charset=utf-8");
        types.put("jpg", "image/jpeg");
        types.put("ico", "image/x-icon");
    }

    public BrowserReference(AbstractBrowserChallenge challenge) {
        this.challenge = challenge;
        canClose.executeWhenReached(new DelayedRunnable(10000) {
            @Override
            public void delayedrun() {
                unregisterRequestHandler();
            }
        });
    }

    protected final AtomicReference<HttpHandlerInfo> handlerInfo = new AtomicReference<HttpHandlerInfo>(null);
    protected final SingleReachableState             canClose    = new SingleReachableState("canClose");
    protected final static Queue                     QUEUE       = new Queue("BrowserReference") {
        @Override
        public void killQueue() {
            LogController.CL().log(new Throwable("YOU CANNOT KILL ME!"));
        }
    };

    public void open() throws IOException {
        if (!canClose.isReached()) {
            QUEUE.addWait(new QueueAction<Void, IOException>() {
                @Override
                protected Void run() throws IOException {
                    synchronized (handlerInfo) {
                        if (handlerInfo.get() == null) {
                            try {
                                int port = BrowserSolverService.getInstance().getConfig().getLocalHttpPort();
                                if (port < 1024) {
                                    port = 0;
                                } else if (port > 65000) {
                                    port = 65000;
                                }
                                handlerInfo.set(DeprecatedAPIHttpServerController.getInstance().registerRequestHandler(port, true, BrowserReference.this));
                            } catch (final IOException e) {
                                getLogger().log(e);
                                handlerInfo.set(DeprecatedAPIHttpServerController.getInstance().registerRequestHandler(0, true, BrowserReference.this));
                            }
                            BrowserSolverService.getInstance().getConfig().setLocalHttpPort(getBasePort());
                        }
                    }
                    openURL(URLHelper.parseLocation(new URL(getBase()), "?id=" + id.getID()));
                    return null;
                }
            });
        }
    }

    protected LogInterface getLogger() {
        LogInterface logger = challenge.getJob().getLogger();
        if (logger == null) {
            final Plugin plugin = challenge.getPlugin();
            if (plugin != null) {
                logger = plugin.getLogger();
            }
            if (logger != null) {
                logger = LogController.CL();
            }
        }
        return logger;
    }

    public String getServerAddress() {
        final HttpHandlerInfo handler = handlerInfo.get();
        if (handler != null) {
            final String ret = handler.getHttpServer().getServerAddress();
            if (ret != null) {
                return ret;
            }
        }
        return "127.0.0.1" + getBasePort();
    }

    public int getBasePort() {
        final HttpHandlerInfo handler = handlerInfo.get();
        if (handler != null) {
            return handler.getPort();
        } else {
            return -1;
        }
    }

    public String getBase() {
        return "http://" + getServerAddress() + "/" + challenge.getHttpPath() + "/";
    }

    protected void openURL(final String url) {
        new Thread("openURL:" + url) {
            {
                setDaemon(true);
            }

            @Override
            public void run() {
                String[] browserCmd = BrowserSolverService.getInstance().getConfig().getBrowserCommandline();
                if (browserCmd == null || browserCmd.length == 0) {
                    browserCmd = CFG_GENERAL.BROWSER_COMMAND_LINE.getValue();
                }
                browserCmd = CrossSystem.buildBrowserCommandline(browserCmd, url);
                if (browserCmd != null && browserCmd.length > 0) {
                    final ProcessBuilder pb = ProcessBuilderFactory.create(browserCmd);
                    pb.redirectErrorStream(true);
                    try {
                        pb.start();
                        return;
                    } catch (IOException e) {
                        getLogger().log(e);
                    }
                }
                CrossSystem.openURL(url);
            }
        }.start();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            dispose();
        } finally {
            super.finalize();
        }
    }

    public void dispose() {
        canClose.setReached();
    }

    protected void unregisterRequestHandler() {
        QUEUE.add(new QueueAction<Void, RuntimeException>() {
            @Override
            protected Void run() throws RuntimeException {
                final HttpHandlerInfo lHandlerInfo;
                synchronized (handlerInfo) {
                    lHandlerInfo = handlerInfo.getAndSet(null);
                }
                if (lHandlerInfo != null) {
                    DeprecatedAPIHttpServerController.getInstance().unregisterRequestHandler(lHandlerInfo);
                }
                return null;
            }
        });
    }

    @Override
    public void onBeforeRequest(HttpRequest request, HttpResponse response) {
        response.setHook(this);
    }

    @Override
    public void onBeforeSendHeaders(HttpResponse response) {
        HttpRequest request = response.getConnection().getRequest();
        response.getResponseHeaders().add(new HTTPHeader(HTTPConstants.HEADER_RESPONSE_ACCESS_CONTROL_ALLOW_ORIGIN, "http://" + getServerAddress()));
        // ContentSecurityHeader csp = new ContentSecurityHeader();
        // csp.addDefaultSrc("'self'");
        // csp.addDefaultSrc("'unsafe-inline'");
        // csp.addDefaultSrc("https://fonts.googleapis.com");
        // csp.addDefaultSrc("https://fonts.gstatic.com");
        // csp.addDefaultSrc("http://www.sweetcaptcha.com");
        // csp.addDefaultSrc("http://code.jquery.com/jquery-1.10.2.min.js");
        // csp.addDefaultSrc("http://sweetcaptcha.s3.amazonaws.com");
        // response.getResponseHeaders().add(new HTTPHeader(HTTPConstants.HEADER_RESPONSE_CONTENT_SECURITY_POLICY, csp.toHeaderString()));
        response.getResponseHeaders().add(new HTTPHeader(HTTPConstants.HEADER_RESPONSE_X_FRAME_OPTIONS, "SAMEORIGIN"));
        response.getResponseHeaders().add(new HTTPHeader(HTTPConstants.HEADER_RESPONSE_X_XSS_PROTECTION, "1; mode=block"));
        response.getResponseHeaders().add(new HTTPHeader(HTTPConstants.HEADER_RESPONSE_X_CONTENT_TYPE_OPTIONS, "nosniff"));
    }

    @Override
    public void onAfterRequest(HttpRequest request, HttpResponse response, boolean handled) {
        if (!handled) {
            response.setHook(null);
        }
    }

    @Override
    public void onAfterRequestException(HttpRequest request, HttpResponse response, Throwable e) {
    }

    protected String lastRequestString = null;

    public static int getHighestBrowserExtensionVersion() {
        return HIGHEST_BROWSER_EXTENSION_VERSION.get();
    }

    private final static AtomicInteger HIGHEST_BROWSER_EXTENSION_VERSION = new AtomicInteger(-1);

    /*
     * (non-Javadoc)
     *
     * @see org.appwork.utils.net.httpserver.handler.HttpRequestHandler#onGetRequest(org.appwork.utils.net.httpserver.requests.GetRequest,
     * org.appwork.utils.net.httpserver.responses.HttpResponse)
     */
    @Override
    public boolean onGetRequest(GetRequest request, HttpResponse response) throws BasicRemoteAPIException {
        try {
            final String XMyjdAppkey = request.getRequestHeaders().getValue("X-Myjd-Appkey");
            final String version[] = new Regex(XMyjdAppkey, "(\\d+)(\\.|$)").getColumn(0);
            if (version != null && version.length == 3) {
                int ver = Integer.parseInt(version[0]) * 10000;
                ver += Integer.parseInt(version[1]) * 100;
                ver += Integer.parseInt(version[2]);
                final int highest = HIGHEST_BROWSER_EXTENSION_VERSION.get();
                if (ver > highest) {
                    HIGHEST_BROWSER_EXTENSION_VERSION.compareAndSet(highest, ver);
                }
            }
            synchronized (BrowserReference.this) {
                final String requestString = request.getRemoteAddress() + "\r\n" + request.getRequestedURL() + "\r\n" + request.getRequestHeaders();
                if (!StringUtils.equals(lastRequestString, requestString)) {
                    lastRequestString = requestString;
                    getLogger().info(requestString);
                }
            }
            HTTPHeader originHeader = request.getRequestHeaders().get(HTTPConstants.HEADER_REQUEST_ORIGIN);
            // todo: origin check
            if ("/resource".equals(request.getRequestedPath())) {
                String resourceID = new Regex(request.getRequestedURLParameters().get(0).value, "([^\\?]+)").getMatch(0);
                URL resource = resourceIds.get(resourceID);
                if (resource != null) {
                    response.setResponseCode(ResponseCode.SUCCESS_OK);
                    response.getResponseHeaders().add(new HTTPHeader(HTTPConstants.HEADER_RESPONSE_CONTENT_TYPE, types.get(Files.getExtension(resourceID))));
                    System.out.println(resource);
                    response.getOutputStream(true).write(IO.readURL(resource));
                    return true;
                }
            }
            if (request.getRequestedPath() != null && !request.getRequestedPath().matches("^/" + Pattern.quote(challenge.getHttpPath()) + "/.*$")) {
                return false;
            }
            // custom
            final boolean custom = challenge.onRawGetRequest(this, request, response);
            if (custom) {
                return true;
            }
            final String pDo = request.getParameterbyKey("do");
            final String id = request.getParameterbyKey("id");
            final String skipType = request.getParameterbyKey("skiptype");
            final String useractive = request.getParameterbyKey("useractive");
            if (!StringUtils.equals(id, Long.toString(this.id.getID()))) {
                return false;
            }
            response.setResponseCode(ResponseCode.SUCCESS_OK);
            response.getResponseHeaders().add(new HTTPHeader(HTTPConstants.HEADER_RESPONSE_CONTENT_TYPE, "text/html; charset=utf-8"));
            if ("loaded".equals(pDo)) {
                final HTTPHeader ua = request.getRequestHeaders().get("User-Agent");
                final BrowserCaptchaSolverConfig config = BrowserSolverService.getInstance().getConfig();
                if (config.isAutoClickEnabled()) {
                    // let bounds = element.getBoundingClientRect();
                    //
                    // let w = Math.max(document.documentElement.clientWidth, window.innerWidth || 0);
                    // let h = Math.max(document.documentElement.clientHeight, window.innerHeight || 0);
                    // /*
                    // * If the browser does not support screenX and screen Y, use screenLeft and
                    // * screenTop instead (and vice versa)
                    // */
                    // let winLeft = window.screenX ? window.screenX : window.screenLeft;
                    // let winTop = window.screenY ? window.screenY : window.screenTop;
                    // let windowWidth = window.outerWidth;
                    // let windowHeight = window.outerHeight;
                    // let ie = getInternetExplorerVersion();
                    // if (ie > 0) {
                    // if (ie >= 10) {
                    // // bug in ie 10 and 11
                    // let zoom = screen.deviceXDPI / screen.logicalXDPI;
                    // winLeft *= zoom;
                    // winTop *= zoom;
                    // windowWidth *= zoom;
                    // windowHeight *= zoom;
                    // }
                    // }
                    // let loadedParams = Object.create(null);
                    // loadedParams.x = winLeft;
                    // loadedParams.y = winTop;
                    // loadedParams.w = windowWidth;
                    // loadedParams.h = windowHeight;
                    // loadedParams.vw = w;
                    // loadedParams.vh = h;
                    // loadedParams.eleft = bounds.left;
                    // loadedParams.etop = bounds.top;
                    // loadedParams.ew = bounds.width;
                    // loadedParams.eh = bounds.height;
                    // loadedParams.dpi = window.devicePixelRatio;
                    try {
                        final int x = (int) Double.parseDouble(request.getParameterbyKey("x"));
                        final int y = (int) Double.parseDouble(request.getParameterbyKey("y"));
                        final int w = (int) Double.parseDouble(request.getParameterbyKey("w"));
                        final int h = (int) Double.parseDouble(request.getParameterbyKey("h"));
                        final int vw = (int) Double.parseDouble(request.getParameterbyKey("vw"));
                        final int vh = (int) Double.parseDouble(request.getParameterbyKey("vh"));
                        final Double dpi = request.getParameterbyKey("dpi") != null && !StringUtils.equalsIgnoreCase(request.getParameterbyKey("dpi"), "undefined") ? Double.valueOf(request.getParameterbyKey("dpi")) : null;
                        final BrowserWindow browserWindow = new BrowserWindow(ua == null ? null : ua.getValue(), x, y, w, h, vw, vh, dpi) {
                            @Override
                            protected LogInterface getLogger() {
                                return BrowserReference.this.getLogger();
                            }
                        };
                        try {
                            final int delay = Math.max(0, config.getAutoClickDelay());
                            if (delay > 0) {
                                getLogger().info("Delay AutoClick:" + delay);
                                Thread.sleep(delay);
                            }
                        } catch (InterruptedException e) {
                            getLogger().log(e);
                        }
                        if (CrossSystem.isUnix()) {
                            // new Robot().createScreenCapture may crash the VM, auto disable before to avoid crashing again and again...
                            config.setAutoClickEnabled(false);
                            config._getStorageHandler().write();
                        }
                        try {
                            final int eleft = (int) Double.parseDouble(request.getParameterbyKey("eleft"));
                            final int etop = (int) Double.parseDouble(request.getParameterbyKey("etop"));
                            final int ew = (int) Double.parseDouble(request.getParameterbyKey("ew"));
                            final int eh = (int) Double.parseDouble(request.getParameterbyKey("eh"));
                            final Rectangle elementBounds = new Rectangle(eleft, etop, ew, eh);
                            getLogger().info("Rectangle:" + elementBounds);
                            final BrowserViewport viewport = challenge.getBrowserViewport(browserWindow, elementBounds);
                            if (viewport != null) {
                                viewport.onLoaded();
                            }
                        } finally {
                            config.setAutoClickEnabled(true);
                        }
                    } catch (Throwable e) {
                        getLogger().log(e);
                    }
                    response.getOutputStream(true).write("Thanks".getBytes("UTF-8"));
                }
                return true;
            } else if ("canClose".equals(pDo)) {
                if (useractive != null) {
                    ChallengeResponseController.getInstance().keepAlivePendingChallenges(challenge);
                }
                final SolverJob<?> job = ChallengeResponseController.getInstance().getJobByChallengeId(challenge.getId().getID());
                if (challenge.isSolved() || job == null || job.isDone() || BrowserSolver.getInstance().isJobDone(job)) {
                    response.getOutputStream(true).write("true".getBytes("UTF-8"));
                    canClose.setReached();
                    return true;
                } else {
                    response.getOutputStream(true).write("false".getBytes("UTF-8"));
                }
            } else if ("skip".equals(pDo)) {
                final ChallengeResponseController challengeResponseController = ChallengeResponseController.getInstance();
                final SolverJob<?> job = challengeResponseController.getJobByChallengeId(challenge.getId().getID());
                if (job != null) {
                    final BrowserSolver browserSolver = BrowserSolver.getInstance();
                    final Challenge<?> challenge = job.getChallenge();
                    if ("all".equals(skipType)) {
                        challengeResponseController.setSkipRequest(SkipRequest.BLOCK_ALL_CAPTCHAS, browserSolver, challenge);
                    } else if ("hoster".equals(skipType)) {
                        challengeResponseController.setSkipRequest(SkipRequest.BLOCK_HOSTER, browserSolver, challenge);
                    } else if ("package".equals(skipType)) {
                        challengeResponseController.setSkipRequest(SkipRequest.BLOCK_PACKAGE, browserSolver, challenge);
                    } else if ("single".equals(skipType)) {
                        challengeResponseController.setSkipRequest(SkipRequest.SINGLE, browserSolver, challenge);
                    }
                }
                response.getOutputStream(true).write("true".getBytes("UTF-8"));
                return true;
            } else if ("unload".equals(pDo)) {
                response.getOutputStream(true).write("true".getBytes("UTF-8"));
                return true;
            } else if (pDo == null) {
                response.getOutputStream(true).write(challenge.getHTML(request, String.valueOf(this.id.getID())).getBytes("UTF-8"));
            } else {
                return challenge.onGetRequest(this, request, response);
            }
            return true;
        } catch (Throwable e) {
            getLogger().log(e);
            error(response, e);
            return true;
        }
    }

    private void error(HttpResponse response, Throwable e) {
        try {
            response.setResponseCode(ResponseCode.SERVERERROR_INTERNAL);
            response.getResponseHeaders().add(new HTTPHeader(HTTPConstants.HEADER_RESPONSE_CONTENT_TYPE, "text/html; charset=utf-8"));
            response.getOutputStream(true).write(Exceptions.getStackTrace(e).getBytes("UTF-8"));
        } catch (Throwable e1) {
            throw new WTFException(e1);
        }
    }

    @Override
    public boolean onPostRequest(PostRequest request, HttpResponse response) throws BasicRemoteAPIException {
        synchronized (BrowserReference.this) {
            final String requestString = request.getRemoteAddress() + "\r\n" + request.getRequestedURL() + "\r\n" + request.getRequestHeaders();
            if (!StringUtils.equals(lastRequestString, requestString)) {
                lastRequestString = requestString;
                getLogger().info(requestString);
            }
        }
        if (request.getRequestedPath() != null && !request.getRequestedPath().matches("^/" + Pattern.quote(challenge.getHttpPath()) + "/.*$")) {
            return false;
        }
        try {
            // custom
            final boolean custom = challenge.onRawPostRequest(this, request, response);
            if (custom) {
                return true;
            }
            String pDo = request.getParameterbyKey("do");
            String id = request.getParameterbyKey("id");
            if (!StringUtils.equals(id, this.id.getID() + "")) {
                return false;
            }
            return challenge.onPostRequest(this, request, response);
        } catch (Throwable e) {
            getLogger().log(e);
            error(response, e);
            return true;
        }
    }

    public abstract void onResponse(String request);
}
