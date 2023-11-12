package org.jdownloader.captcha.v2.solver.browser;

import java.awt.Rectangle;
import java.io.IOException;

import jd.http.Browser;
import jd.plugins.Plugin;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;

import org.appwork.remoteapi.exceptions.RemoteAPIException;
import org.appwork.utils.logging2.LogInterface;
import org.appwork.utils.net.httpserver.requests.GetRequest;
import org.appwork.utils.net.httpserver.requests.HttpRequest;
import org.appwork.utils.net.httpserver.requests.PostRequest;
import org.appwork.utils.net.httpserver.responses.HttpResponse;
import org.jdownloader.captcha.v2.Challenge;
import org.jdownloader.captcha.v2.solverjob.ResponseList;
import org.jdownloader.logging.LogController;

public abstract class AbstractBrowserChallenge extends Challenge<String> {
    protected final Plugin  plugin;
    protected final Browser pluginBrowser;

    public Plugin getPlugin() {
        return plugin;
    }

    public Browser getPluginBrowser() {
        return pluginBrowser;
    }

    public boolean isSolved() {
        final ResponseList<String> results = getResult();
        return results != null && results.getValue() != null;
    }

    protected AbstractBrowserChallenge(final String method, final Plugin plugin, Browser pluginBrowser) {
        super(method, null);
        this.plugin = plugin;
        this.pluginBrowser = pluginBrowser;
    }

    public AbstractBrowserChallenge(final String method, final Plugin plugin) {
        super(method, null);
        if (plugin == null) {
            this.plugin = Plugin.getCurrentActivePlugin();
        } else {
            this.plugin = plugin;
        }
        if (this.plugin instanceof PluginForHost) {
            this.pluginBrowser = ((PluginForHost) this.plugin).getBrowser();
        } else if (this.plugin instanceof PluginForDecrypt) {
            this.pluginBrowser = ((PluginForDecrypt) this.plugin).getBrowser();
        } else {
            this.pluginBrowser = null;
        }
    }

    protected LogInterface getLogger() {
        LogInterface ret = null;
        if (plugin != null) {
            ret = plugin.getLogger();
            if (ret == null) {
                ret = LogController.CL();
            }
        }
        return ret;
    }

    abstract public String getHTML(HttpRequest request, String id);

    abstract public BrowserViewport getBrowserViewport(BrowserWindow screenResource, Rectangle elementBounds);

    public boolean onGetRequest(BrowserReference browserReference, GetRequest request, HttpResponse response) throws IOException, RemoteAPIException {
        return false;
    }

    public boolean onPostRequest(BrowserReference browserReference, PostRequest request, HttpResponse response) throws IOException, RemoteAPIException {
        return false;
    }

    public boolean onRawPostRequest(final BrowserReference browserRefefence, final PostRequest request, final HttpResponse response) throws IOException, RemoteAPIException {
        return false;
    }

    public boolean onRawGetRequest(final BrowserReference browserReference, final GetRequest request, final HttpResponse response) throws IOException, RemoteAPIException {
        return false;
    }

    abstract protected String getCaptchaNameSpace();

    protected String getHttpPath() {
        if (plugin != null) {
            return "captcha/" + getCaptchaNameSpace() + "/" + plugin.getHost();
        } else {
            return "captcha/" + getCaptchaNameSpace() + "/jd";
        }
    }
}
