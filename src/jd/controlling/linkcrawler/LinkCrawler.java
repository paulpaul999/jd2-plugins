package jd.controlling.linkcrawler;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import org.appwork.net.protocol.http.HTTPConstants;
import org.appwork.scheduler.DelayedRunnable;
import org.appwork.storage.config.JsonConfig;
import org.appwork.utils.DebugMode;
import org.appwork.utils.Files;
import org.appwork.utils.IO;
import org.appwork.utils.Regex;
import org.appwork.utils.StringUtils;
import org.appwork.utils.Time;
import org.appwork.utils.encoding.Base64;
import org.appwork.utils.logging2.ClearableLogInterface;
import org.appwork.utils.logging2.ClosableLogInterface;
import org.appwork.utils.logging2.LogInterface;
import org.appwork.utils.logging2.LogSource;
import org.appwork.utils.net.URLHelper;
import org.appwork.utils.net.httpconnection.HTTPConnectionUtils.DispositionHeader;
import org.appwork.utils.os.CrossSystem;
import org.jdownloader.controlling.UniqueAlltimeID;
import org.jdownloader.controlling.UrlProtection;
import org.jdownloader.logging.LogController;
import org.jdownloader.myjdownloader.client.json.AvailableLinkState;
import org.jdownloader.plugins.controller.LazyPlugin;
import org.jdownloader.plugins.controller.LazyPlugin.FEATURE;
import org.jdownloader.plugins.controller.PluginClassLoader;
import org.jdownloader.plugins.controller.PluginClassLoader.PluginClassLoaderChild;
import org.jdownloader.plugins.controller.UpdateRequiredClassNotFoundException;
import org.jdownloader.plugins.controller.container.ContainerPluginController;
import org.jdownloader.plugins.controller.crawler.CrawlerPluginController;
import org.jdownloader.plugins.controller.crawler.LazyCrawlerPlugin;
import org.jdownloader.plugins.controller.host.HostPluginController;
import org.jdownloader.plugins.controller.host.LazyHostPlugin;
import org.jdownloader.settings.GeneralSettings;

import jd.controlling.linkcollector.LinkCollectingJob;
import jd.controlling.linkcollector.LinkCollector.JobLinkCrawler;
import jd.controlling.linkcollector.LinknameCleaner;
import jd.controlling.linkcrawler.LinkCrawlerConfig.DirectHTTPPermission;
import jd.controlling.linkcrawler.LinkCrawlerRule.RULE;
import jd.http.Browser;
import jd.http.Request;
import jd.http.URLConnectionAdapter;
import jd.http.requests.PostRequest;
import jd.nutils.SimpleFTP;
import jd.nutils.encoding.Encoding;
import jd.parser.html.Form;
import jd.parser.html.HTMLParser;
import jd.parser.html.HTMLParser.HtmlParserCharSequence;
import jd.parser.html.HTMLParser.HtmlParserResultSet;
import jd.plugins.CryptedLink;
import jd.plugins.DownloadLink;
import jd.plugins.FilePackage;
import jd.plugins.Plugin;
import jd.plugins.PluginForDecrypt;
import jd.plugins.PluginForHost;
import jd.plugins.PluginsC;
import jd.plugins.hoster.DirectHTTP;

public class LinkCrawler {
    private static enum DISTRIBUTE {
        STOP,
        BLACKLISTED,
        NEXT,
        CONTINUE
    }

    protected static enum DUPLICATE {
        CONTAINER,
        CRAWLER,
        FINAL,
        DEEP
    }

    private final static String                                           DIRECT_HTTP                 = "directhttp";
    private final static String                                           HTTP_LINKS                  = "http links";
    private final static int                                              MAX_THREADS;
    private java.util.List<CrawledLink>                                   crawledLinks                = new ArrayList<CrawledLink>();
    private AtomicInteger                                                 crawledLinksCounter         = new AtomicInteger(0);
    private java.util.List<CrawledLink>                                   filteredLinks               = new ArrayList<CrawledLink>();
    private AtomicInteger                                                 filteredLinksCounter        = new AtomicInteger(0);
    private java.util.List<CrawledLink>                                   brokenLinks                 = new ArrayList<CrawledLink>();
    private AtomicInteger                                                 brokenLinksCounter          = new AtomicInteger(0);
    private java.util.List<CrawledLink>                                   unhandledLinks              = new ArrayList<CrawledLink>();
    private final AtomicInteger                                           unhandledLinksCounter       = new AtomicInteger(0);
    private final AtomicInteger                                           processedLinksCounter       = new AtomicInteger(0);
    private final List<LinkCrawlerTask>                                   tasks                       = new ArrayList<LinkCrawlerTask>();
    private final static Set<LinkCrawler>                                 CRAWLER                     = new HashSet<LinkCrawler>();
    private final Map<String, Object>                                     duplicateFinderContainer;
    private final Map<LazyCrawlerPlugin, Set<String>>                     duplicateFinderCrawler;
    private final Map<String, CrawledLink>                                duplicateFinderFinal;
    private final Map<String, Object>                                     duplicateFinderDeep;
    private final Map<CrawledLink, Object>                                loopPreventionEmbedded;
    private LinkCrawlerHandler                                            handler                     = null;
    protected static final ThreadPoolExecutor                             threadPool;
    private LinkCrawlerFilter                                             filter                      = null;
    private final LinkCrawler                                             parentCrawler;
    private final long                                                    created;
    public final static String                                            PACKAGE_ALLOW_MERGE         = "ALLOW_MERGE";
    public final static String                                            PACKAGE_ALLOW_INHERITANCE   = "ALLOW_INHERITANCE";
    public final static String                                            PACKAGE_CLEANUP_NAME        = "CLEANUP_NAME";
    public final static String                                            PACKAGE_IGNORE_VARIOUS      = "PACKAGE_IGNORE_VARIOUS";
    public final static String                                            PROPERTY_AUTO_REFERER       = "autoReferer";
    public static final UniqueAlltimeID                                   PERMANENT_OFFLINE_ID        = new UniqueAlltimeID();
    private boolean                                                       doDuplicateFinderFinalCheck = true;
    private List<LazyCrawlerPlugin>                                       unsortedLazyCrawlerPlugins;
    protected final PluginClassLoaderChild                                classLoader;
    private final String                                                  defaultDownloadFolder;
    private final AtomicReference<List<LazyCrawlerPlugin>>                sortedLazyCrawlerPlugins    = new AtomicReference<List<LazyCrawlerPlugin>>();
    private final AtomicReference<List<LazyHostPlugin>>                   sortedLazyHostPlugins       = new AtomicReference<List<LazyHostPlugin>>();
    private final AtomicReference<List<LinkCrawlerRule>>                  linkCrawlerRules            = new AtomicReference<List<LinkCrawlerRule>>();
    private LinkCrawlerDeepInspector                                      deepInspector               = null;
    private DirectHTTPPermission                                          directHTTPPermission        = DirectHTTPPermission.ALWAYS;
    protected final UniqueAlltimeID                                       uniqueAlltimeID             = new UniqueAlltimeID();
    protected final WeakHashMap<LinkCrawler, Object>                      children                    = new WeakHashMap<LinkCrawler, Object>();
    protected final static WeakHashMap<LinkCrawler, Set<LinkCrawlerLock>> LOCKS                       = new WeakHashMap<LinkCrawler, Set<LinkCrawlerLock>>();
    protected final AtomicReference<LinkCrawlerGeneration>                linkCrawlerGeneration       = new AtomicReference<LinkCrawlerGeneration>(null);
    protected final static WeakHashMap<LinkCrawler, Map<String, Object>>  CRAWLER_CACHE               = new WeakHashMap<LinkCrawler, Map<String, Object>>();

    public Object getCrawlerCache(final String key) {
        synchronized (CRAWLER_CACHE) {
            final Map<String, Object> cache = CRAWLER_CACHE.get(this);
            return cache != null ? cache.get(key) : null;
        }
    }

    public Object putCrawlerCache(final String key, Object value) {
        synchronized (CRAWLER_CACHE) {
            Map<String, Object> cache = CRAWLER_CACHE.get(this);
            if (cache == null) {
                cache = new HashMap<String, Object>();
                CRAWLER_CACHE.put(this, cache);
            }
            return cache.put(key, value);
        }
    }

    protected LinkCrawlerGeneration getValidLinkCrawlerGeneration() {
        synchronized (linkCrawlerGeneration) {
            LinkCrawlerGeneration ret = linkCrawlerGeneration.get();
            if (ret == null || !ret.isValid()) {
                ret = new LinkCrawlerGeneration();
                linkCrawlerGeneration.set(ret);
            }
            return ret;
        }
    }

    protected LinkCrawlerGeneration getCurrentLinkCrawlerGeneration() {
        synchronized (linkCrawlerGeneration) {
            final LinkCrawlerGeneration ret = linkCrawlerGeneration.get();
            return ret;
        }
    }

    public class LinkCrawlerTask {
        private final AtomicBoolean         runningFlag = new AtomicBoolean(true);
        private final LinkCrawlerGeneration generation;
        private final LinkCrawler           crawler;
        private final String                taskID;

        public final LinkCrawler getCrawler() {
            return crawler;
        }

        protected LinkCrawlerTask(LinkCrawler linkCrawler, LinkCrawlerGeneration generation, String taskID) {
            this.generation = generation;
            this.crawler = linkCrawler;
            this.taskID = taskID + ":" + UniqueAlltimeID.next();
        }

        public String getTaskID() {
            return taskID;
        }

        public LinkCrawlerGeneration getLinkCrawlerGeneration() {
            return generation;
        }

        public final boolean isRunning() {
            return runningFlag.get();
        }

        protected final boolean invalidate() {
            return runningFlag.compareAndSet(true, false);
        }
    }

    public class LinkCrawlerGeneration {
        private final AtomicBoolean validFlag = new AtomicBoolean(true);

        public final boolean isValid() {
            return validFlag.get() && LinkCrawler.this.linkCrawlerGeneration.get() == this;
        }

        protected final void invalidate() {
            validFlag.set(false);
        }
    }

    protected LinkCrawlerLock getLinkCrawlerLock(final LazyCrawlerPlugin plugin, final CrawledLink crawledLink) {
        synchronized (LOCKS) {
            LinkCrawlerLock ret = null;
            // find best matching LinkCrawlerLock
            for (final Set<LinkCrawlerLock> locks : LOCKS.values()) {
                for (final LinkCrawlerLock lock : locks) {
                    if (ret != lock && (ret == null || lock.getMaxConcurrency() < ret.getMaxConcurrency() && lock.matches(plugin, crawledLink))) {
                        ret = lock;
                    }
                }
            }
            if (ret == null && LinkCrawlerLock.requiresLocking(plugin)) {
                // create new LinkCrawlerLock
                ret = new LinkCrawlerLock(plugin);
            }
            if (ret != null) {
                // share LinkCrawlerLock to all LinkCrawler roots
                for (final LinkCrawler linkCrawler : LOCKS.keySet()) {
                    addSequentialLockObject(linkCrawler.getRoot(), ret);
                }
            }
            return ret;
        }
    }

    protected void addSequentialLockObject(final LinkCrawler linkCrawler, final LinkCrawlerLock lock) {
        if (lock != null) {
            synchronized (LOCKS) {
                final LinkCrawler root = linkCrawler != null ? linkCrawler : getRoot();
                Set<LinkCrawlerLock> locks = LOCKS.get(root);
                if (locks == null) {
                    locks = new HashSet<LinkCrawlerLock>();
                    LOCKS.put(root, locks);
                }
                locks.add(lock);
            }
        }
    }

    public void addSequentialLockObject(final LinkCrawlerLock lock) {
        addSequentialLockObject(getRoot(), lock);
    }

    public UniqueAlltimeID getUniqueAlltimeID() {
        return uniqueAlltimeID;
    }

    public LinkCrawler getParent() {
        return parentCrawler;
    }

    private final static LinkCrawlerConfig CONFIG = JsonConfig.create(LinkCrawlerConfig.class);

    public static LinkCrawlerConfig getConfig() {
        return CONFIG;
    }

    public void setDirectHTTPPermission(DirectHTTPPermission directHTTPPermission) {
        if (directHTTPPermission == null) {
            this.directHTTPPermission = DirectHTTPPermission.ALWAYS;
        } else {
            this.directHTTPPermission = directHTTPPermission;
        }
    }

    protected final static ScheduledExecutorService TIMINGQUEUE = DelayedRunnable.getNewScheduledExecutorService();

    public boolean isDoDuplicateFinderFinalCheck() {
        final LinkCrawler parent = getParent();
        if (parent != null) {
            return parent.isDoDuplicateFinderFinalCheck();
        } else {
            return doDuplicateFinderFinalCheck;
        }
    }

    protected Long getDefaultAverageRuntime() {
        return null;
    }

    public static int getMaxThreads() {
        return MAX_THREADS;
    }

    static {
        MAX_THREADS = Math.max(CONFIG.getMaxThreads(), 1);
        final int keepAlive = Math.max(CONFIG.getThreadKeepAlive(), 100);
        /**
         * PriorityBlockingQueue leaks last Item for some java versions
         *
         * http://bugs.java.com/bugdatabase/view_bug.do?bug_id=7161229
         */
        threadPool = new ThreadPoolExecutor(MAX_THREADS, MAX_THREADS, keepAlive, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<Runnable>(100, new Comparator<Runnable>() {
            public int compare(Runnable o1, Runnable o2) {
                if (o1 == o2) {
                    return 0;
                }
                long l1 = ((LinkCrawlerRunnable) o1).getAverageRuntime();
                long l2 = ((LinkCrawlerRunnable) o2).getAverageRuntime();
                return (l1 < l2) ? -1 : ((l1 == l2) ? 0 : 1);
            }
        }), new ThreadFactory() {
            public Thread newThread(Runnable r) {
                /*
                 * our thread factory so we have logger,browser settings available
                 */
                return new LinkCrawlerThread(r);
            }
        }, new ThreadPoolExecutor.AbortPolicy());
        threadPool.allowCoreThreadTimeOut(true);
    }
    private final static LinkCrawlerEventSender EVENTSENDER = new LinkCrawlerEventSender();

    public static LinkCrawlerEventSender getGlobalEventSender() {
        return EVENTSENDER;
    }

    public static LinkCrawler newInstance() {
        return newInstance(null, null);
    }

    public static LinkCrawler newInstance(final Boolean connectParent, final Boolean avoidDuplicates) {
        final LinkCrawler lc;
        if (Thread.currentThread() instanceof LinkCrawlerThread) {
            final LinkCrawlerThread thread = (LinkCrawlerThread) (Thread.currentThread());
            final Object owner = thread.getCurrentOwner();
            final CrawledLink source;
            if (owner instanceof PluginForDecrypt) {
                source = ((PluginForDecrypt) owner).getCurrentLink();
            } else if (owner instanceof PluginsC) {
                source = ((PluginsC) owner).getCurrentLink();
            } else {
                source = null;
            }
            final LinkCrawler parent = thread.getCurrentLinkCrawler();
            lc = new LinkCrawler(connectParent == null ? false : connectParent.booleanValue(), avoidDuplicates == null ? false : avoidDuplicates.booleanValue()) {
                @Override
                protected void attachLinkCrawler(final LinkCrawler linkCrawler) {
                    if (linkCrawler != null && linkCrawler != this) {
                        if (parent != null) {
                            parent.attachLinkCrawler(linkCrawler);
                        }
                        synchronized (children) {
                            children.put(linkCrawler, Boolean.TRUE);
                        }
                    }
                }

                @Override
                protected CrawledLink crawledLinkFactorybyURL(final CharSequence url) {
                    final CrawledLink ret;
                    if (parent != null) {
                        ret = parent.crawledLinkFactorybyURL(url);
                    } else {
                        ret = new CrawledLink(url);
                    }
                    if (source != null) {
                        ret.setSourceLink(source);
                    }
                    return ret;
                }

                @Override
                protected boolean distributeCrawledLink(CrawledLink crawledLink) {
                    return crawledLink != null && crawledLink.getSourceUrls() == null;
                }

                @Override
                protected void postprocessFinalCrawledLink(CrawledLink link) {
                }
            };
            parent.attachLinkCrawler(lc);
        } else {
            lc = new LinkCrawler(connectParent == null ? true : connectParent.booleanValue(), avoidDuplicates == null ? true : avoidDuplicates.booleanValue());
        }
        return lc;
    }

    protected PluginClassLoaderChild getPluginClassLoaderChild() {
        return classLoader;
    }

    public boolean addLinkCrawlerRule(LinkCrawlerRule rule) {
        if (rule != null) {
            boolean refresh = false;
            try {
                synchronized (LINKCRAWLERRULESLOCK) {
                    List<LinkCrawlerRuleStorable> rules = CONFIG.getLinkCrawlerRules();
                    if (rules == null) {
                        rules = new ArrayList<LinkCrawlerRuleStorable>();
                    }
                    for (final LinkCrawlerRuleStorable existingRule : rules) {
                        if (existingRule.getId() == rule.getId() || (existingRule.getRule() == rule.getRule() && StringUtils.equals(existingRule.getPattern(), rule.getPattern()))) {
                            return false;
                        }
                    }
                    rules.add(new LinkCrawlerRuleStorable(rule));
                    CONFIG.setLinkCrawlerRules(rules);
                    refresh = true;
                    return true;
                }
            } finally {
                if (refresh) {
                    synchronized (linkCrawlerRules) {
                        linkCrawlerRules.set(null);
                    }
                }
            }
        }
        return false;
    }

    private static final Object LINKCRAWLERRULESLOCK = new Object();

    public void setLinkCrawlerRuleCookies(final long ruleID, final List<String[]> setCookies) {
        boolean refresh = false;
        try {
            synchronized (LINKCRAWLERRULESLOCK) {
                final List<LinkCrawlerRuleStorable> rules = CONFIG.getLinkCrawlerRules();
                if (rules != null) {
                    for (final LinkCrawlerRuleStorable rule : rules) {
                        if (rule.getId() == ruleID) {
                            rule.setCookies(setCookies);
                            CONFIG.setLinkCrawlerRules(new ArrayList<LinkCrawlerRuleStorable>(rules));
                            refresh = true;
                            return;
                        }
                    }
                }
            }
        } finally {
            if (refresh) {
                synchronized (linkCrawlerRules) {
                    linkCrawlerRules.set(null);
                }
            }
        }
    }

    public static List<String[]> getLinkCrawlerRuleCookies(final long ruleID) {
        return getLinkCrawlerRuleCookies(ruleID, false);
    }

    public static LinkCrawlerRule getLinkCrawlerRule(final long ruleID) {
        synchronized (LINKCRAWLERRULESLOCK) {
            final LinkCrawlerRuleStorable storable = getLinkCrawlerRuleStorable(ruleID);
            if (storable != null) {
                try {
                    return storable._getLinkCrawlerRule();
                } catch (final Throwable e) {
                    LogController.CL().log(e);
                }
            }
            return null;
        }
    }

    protected static LinkCrawlerRuleStorable getLinkCrawlerRuleStorable(final long ruleID) {
        synchronized (LINKCRAWLERRULESLOCK) {
            final List<LinkCrawlerRuleStorable> rules = CONFIG.getLinkCrawlerRules();
            if (rules == null || rules.size() == 0) {
                return null;
            } else {
                for (final LinkCrawlerRuleStorable rule : rules) {
                    if (rule.getId() == ruleID) {
                        return rule;
                    }
                }
                return null;
            }
        }
    }

    public static List<String[]> getLinkCrawlerRuleCookies(final long ruleID, final boolean mustBeEnabled) {
        synchronized (LINKCRAWLERRULESLOCK) {
            final LinkCrawlerRuleStorable storable = getLinkCrawlerRuleStorable(ruleID);
            if (storable != null && storable.getCookies() != null && (!mustBeEnabled || storable.isEnabled())) {
                return new ArrayList<String[]>(storable.getCookies());
            } else {
                return null;
            }
        }
    }

    protected List<LinkCrawlerRule> listLinkCrawlerRules() {
        final ArrayList<LinkCrawlerRule> ret = new ArrayList<LinkCrawlerRule>();
        if (CONFIG.isLinkCrawlerRulesEnabled()) {
            synchronized (LINKCRAWLERRULESLOCK) {
                final List<LinkCrawlerRuleStorable> rules = CONFIG.getLinkCrawlerRules();
                if (rules != null) {
                    for (final LinkCrawlerRuleStorable rule : rules) {
                        try {
                            if (rule.isEnabled()) {
                                ret.add(rule._getLinkCrawlerRule());
                            }
                        } catch (final Throwable e) {
                            LogController.CL().log(e);
                        }
                    }
                }
            }
        }
        return ret;
    }

    protected void attachLinkCrawler(final LinkCrawler linkCrawler) {
        if (linkCrawler != null && linkCrawler != this) {
            final LinkCrawler parent = getParent();
            if (parent != null) {
                parent.attachLinkCrawler(linkCrawler);
            }
            synchronized (children) {
                children.put(linkCrawler, Boolean.TRUE);
            }
        }
    }

    protected final AtomicReference<LazyHostPlugin> lazyDirect = new AtomicReference<LazyHostPlugin>();

    protected LazyHostPlugin getDirectHTTPPlugin() {
        if (parentCrawler != null) {
            return parentCrawler.getDirectHTTPPlugin();
        } else {
            LazyHostPlugin ret = lazyDirect.get();
            if (ret == null) {
                ret = HostPluginController.getInstance().get(DIRECT_HTTP);
                lazyDirect.set(ret);
            }
            return ret;
        }
    }

    protected final AtomicReference<LazyHostPlugin> lazyHttp = new AtomicReference<LazyHostPlugin>();

    protected LazyHostPlugin getGenericHttpPlugin() {
        if (parentCrawler != null) {
            return parentCrawler.getGenericHttpPlugin();
        } else {
            LazyHostPlugin ret = lazyHttp.get();
            if (ret == null) {
                ret = HostPluginController.getInstance().get(HTTP_LINKS);
                lazyHttp.set(ret);
            }
            return ret;
        }
    }

    protected final AtomicReference<LazyHostPlugin> lazyFtp = new AtomicReference<LazyHostPlugin>();

    protected LazyHostPlugin getGenericFtpPlugin() {
        if (parentCrawler != null) {
            return parentCrawler.getGenericFtpPlugin();
        } else {
            LazyHostPlugin ret = lazyFtp.get();
            if (ret == null) {
                ret = HostPluginController.getInstance().get("ftp");
                lazyFtp.set(ret);
            }
            return ret;
        }
    }

    protected final AtomicReference<LazyCrawlerPlugin> lazyDeepDecryptHelper = new AtomicReference<LazyCrawlerPlugin>();

    protected LazyCrawlerPlugin getDeepCrawlingPlugin() {
        if (parentCrawler != null) {
            return parentCrawler.getDeepCrawlingPlugin();
        } else {
            LazyCrawlerPlugin ret = lazyDeepDecryptHelper.get();
            if (ret == null) {
                final List<LazyCrawlerPlugin> lazyCrawlerPlugins = getSortedLazyCrawlerPlugins();
                final ListIterator<LazyCrawlerPlugin> it = lazyCrawlerPlugins.listIterator();
                while (it.hasNext()) {
                    final LazyCrawlerPlugin pDecrypt = it.next();
                    if (StringUtils.equals("linkcrawlerdeephelper", pDecrypt.getDisplayName())) {
                        lazyDeepDecryptHelper.set(pDecrypt);
                        return pDecrypt;
                    }
                }
            }
            return ret;
        }
    }

    public LinkCrawler(final boolean connectParentCrawler, final boolean avoidDuplicates) {
        setFilter(defaultFilterFactory());
        final LinkCrawlerThread thread = getCurrentLinkCrawlerThread();
        if (connectParentCrawler && thread != null) {
            /* forward crawlerGeneration from parent to this child */
            this.parentCrawler = thread.getCurrentLinkCrawler();
            this.parentCrawler.attachLinkCrawler(this);
            this.classLoader = parentCrawler.getPluginClassLoaderChild();
            this.directHTTPPermission = parentCrawler.directHTTPPermission;
            this.defaultDownloadFolder = parentCrawler.defaultDownloadFolder;
            this.duplicateFinderContainer = parentCrawler.duplicateFinderContainer;
            this.duplicateFinderCrawler = parentCrawler.duplicateFinderCrawler;
            this.duplicateFinderFinal = parentCrawler.duplicateFinderFinal;
            this.duplicateFinderDeep = parentCrawler.duplicateFinderDeep;
            this.loopPreventionEmbedded = parentCrawler.loopPreventionEmbedded;
            setHandler(parentCrawler.getHandler());
            setDeepInspector(parentCrawler.getDeepInspector());
        } else {
            duplicateFinderContainer = new HashMap<String, Object>();
            duplicateFinderCrawler = new HashMap<LazyCrawlerPlugin, Set<String>>();
            duplicateFinderFinal = new HashMap<String, CrawledLink>();
            duplicateFinderDeep = new HashMap<String, Object>();
            loopPreventionEmbedded = new HashMap<CrawledLink, Object>();
            setHandler(defaultHandlerFactory());
            setDeepInspector(defaultDeepInspector());
            defaultDownloadFolder = JsonConfig.create(GeneralSettings.class).getDefaultDownloadFolder();
            parentCrawler = null;
            classLoader = PluginClassLoader.getInstance().getChild();
        }
        this.created = System.currentTimeMillis();
        this.doDuplicateFinderFinalCheck = avoidDuplicates;
    }

    public long getCreated() {
        final LinkCrawler parent = getParent();
        if (parent != null) {
            return parent.getCreated();
        }
        return created;
    }

    protected CrawledLink crawledLinkFactorybyURL(final CharSequence url) {
        final LinkCrawler parent = getParent();
        if (parent != null) {
            return parent.crawledLinkFactorybyURL(url);
        } else {
            return new CrawledLink(url);
        }
    }

    protected CrawledLink crawledLinkFactorybyDownloadLink(final DownloadLink link) {
        final LinkCrawler parent = getParent();
        if (parent != null) {
            return parent.crawledLinkFactorybyDownloadLink(link);
        } else {
            return new CrawledLink(link);
        }
    }

    protected CrawledLink crawledLinkFactorybyCryptedLink(final CryptedLink link) {
        final LinkCrawler parent = getParent();
        if (parent != null) {
            return parent.crawledLinkFactorybyCryptedLink(link);
        } else {
            return new CrawledLink(link);
        }
    }

    protected CrawledLink crawledLinkFactory(final Object link) {
        final LinkCrawler parent = getParent();
        if (parent != null) {
            return parent.crawledLinkFactory(link);
        } else {
            if (link instanceof DownloadLink) {
                return crawledLinkFactorybyDownloadLink((DownloadLink) link);
            } else if (link instanceof CryptedLink) {
                return crawledLinkFactorybyCryptedLink((CryptedLink) link);
            } else if (link instanceof CharSequence) {
                return crawledLinkFactorybyURL((CharSequence) link);
            } else {
                throw new IllegalArgumentException("Unsupported:" + link);
            }
        }
    }

    public void crawl(String text) {
        crawl(text, null, false);
    }

    private volatile Set<String> crawlerPluginBlacklist = new HashSet<String>();
    private volatile Set<String> hostPluginBlacklist    = new HashSet<String>();

    public void setCrawlerPluginBlacklist(String[] list) {
        HashSet<String> lcrawlerPluginBlacklist = new HashSet<String>();
        if (list != null) {
            for (String s : list) {
                lcrawlerPluginBlacklist.add(s);
            }
        }
        this.crawlerPluginBlacklist = lcrawlerPluginBlacklist;
    }

    public boolean isBlacklisted(LazyCrawlerPlugin plugin) {
        final LinkCrawler parent = getParent();
        if (parent != null && parent.isBlacklisted(plugin)) {
            return true;
        }
        return crawlerPluginBlacklist.contains(plugin.getDisplayName());
    }

    public boolean isBlacklisted(LazyHostPlugin plugin) {
        final LinkCrawler parent = getParent();
        if (parent != null && parent.isBlacklisted(plugin)) {
            return true;
        }
        return hostPluginBlacklist.contains(plugin.getDisplayName());
    }

    public void setHostPluginBlacklist(String[] list) {
        final HashSet<String> lhostPluginBlacklist = new HashSet<String>();
        if (list != null) {
            for (String s : list) {
                lhostPluginBlacklist.add(s);
            }
        }
        this.hostPluginBlacklist = lhostPluginBlacklist;
    }

    public void crawl(final String text, final String url, final boolean allowDeep) {
        if (!StringUtils.isEmpty(text)) {
            final LinkCrawlerGeneration generation = getValidLinkCrawlerGeneration();
            final LinkCrawlerTask task;
            if ((task = checkStartNotify(generation, "crawlText")) != null) {
                try {
                    if (insideCrawlerPlugin()) {
                        final List<CrawledLink> links = find(generation, null, text, url, allowDeep, true);
                        crawl(generation, links);
                    } else {
                        final LinkCrawlerTask innerTask;
                        if ((innerTask = checkStartNotify(generation, task.getTaskID() + "|crawlTextPool")) != null) {
                            threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation, innerTask) {
                                @Override
                                public long getAverageRuntime() {
                                    final Long ret = getDefaultAverageRuntime();
                                    if (ret != null) {
                                        return ret;
                                    } else {
                                        return super.getAverageRuntime();
                                    }
                                }

                                @Override
                                void crawling() {
                                    final java.util.List<CrawledLink> links = find(generation, null, text, url, allowDeep, true);
                                    crawl(generation, links);
                                }
                            });
                        }
                    }
                } finally {
                    checkFinishNotify(task);
                }
            }
        }
    }

    public List<CrawledLink> find(final LinkCrawlerGeneration generation, final CrawledLink source, String text, String baseURL, final boolean allowDeep, final boolean allowInstantCrawl) {
        final CrawledLink baseLink;
        if (StringUtils.isNotEmpty(baseURL)) {
            baseLink = crawledLinkFactorybyURL(baseURL);
        } else {
            baseLink = null;
        }
        final HtmlParserResultSet resultSet;
        if (allowInstantCrawl && getCurrentLinkCrawlerThread() != null && generation != null) {
            resultSet = new HtmlParserResultSet() {
                private final HashSet<HtmlParserCharSequence> fastResults = new HashSet<HtmlParserCharSequence>();

                @Override
                public boolean add(HtmlParserCharSequence e) {
                    if (!generation.isValid()) {
                        throw new RuntimeException("Abort");
                    } else {
                        final boolean ret = super.add(e);
                        if (ret && (!e.contains("...") && ((getBaseURL() != null && !e.equals(getBaseURL())) || Boolean.TRUE.equals(isSkipBaseURL())))) {
                            fastResults.add(e);
                            final CrawledLink crawledLink;
                            if (true || e.getRetainedLength() > 10) {
                                crawledLink = crawledLinkFactorybyURL(e.toURL());
                            } else {
                                crawledLink = crawledLinkFactorybyURL(e.toCharSequenceURL());
                            }
                            crawledLink.setCrawlDeep(allowDeep);
                            if (crawledLink.getSourceLink() == null) {
                                crawledLink.setSourceLink(baseLink);
                            }
                            final ArrayList<CrawledLink> crawledLinks = new ArrayList<CrawledLink>(1);
                            crawledLinks.add(crawledLink);
                            crawl(generation, crawledLinks);
                        }
                        return ret;
                    }
                };

                private LogSource logger = null;

                @Override
                public LogInterface getLogger() {
                    if (logger == null) {
                        logger = LogController.getInstance().getClassLogger(LinkCrawler.class);
                    }
                    return logger;
                }

                @Override
                protected Collection<String> exportResults() {
                    final ArrayList<String> ret = new ArrayList<String>();
                    outerLoop: for (final HtmlParserCharSequence result : this.getResults()) {
                        if (!fastResults.contains(result)) {
                            final int index = result.indexOf("...");
                            if (index > 0) {
                                final HtmlParserCharSequence check = result.subSequence(0, index);
                                for (final HtmlParserCharSequence fastResult : fastResults) {
                                    if (fastResult.startsWith(check) && result != fastResult && !fastResult.contains("...")) {
                                        continue outerLoop;
                                    }
                                }
                            }
                            ret.add(result.toURL());
                        }
                    }
                    return ret;
                }
            };
        } else {
            resultSet = new HtmlParserResultSet() {
                private LogSource logger = null;

                @Override
                public boolean add(HtmlParserCharSequence e) {
                    if (!generation.isValid()) {
                        throw new RuntimeException("Abort");
                    } else {
                        return super.add(e);
                    }
                }

                @Override
                public LogInterface getLogger() {
                    if (logger == null) {
                        logger = LogController.getInstance().getClassLogger(LinkCrawler.class);
                    }
                    return logger;
                }
            };
        }
        final LinkCrawlerTask task;
        if ((task = checkStartNotify(generation, "find")) != null) {
            try {
                final String[] possibleLinks = HTMLParser.getHttpLinks(preprocessFind(text, baseURL, allowDeep), baseURL, resultSet);
                if (possibleLinks != null && possibleLinks.length > 0) {
                    final List<CrawledLink> possibleCryptedLinks = new ArrayList<CrawledLink>(possibleLinks.length);
                    for (final String possibleLink : possibleLinks) {
                        final CrawledLink crawledLink = crawledLinkFactorybyURL(possibleLink);
                        crawledLink.setCrawlDeep(allowDeep);
                        if (crawledLink.getSourceLink() == null) {
                            crawledLink.setSourceLink(baseLink);
                        }
                        possibleCryptedLinks.add(crawledLink);
                    }
                    return possibleCryptedLinks;
                }
            } catch (RuntimeException e) {
                if (generation.isValid()) {
                    resultSet.getLogger().log(e);
                }
            } finally {
                checkFinishNotify(task);
            }
        }
        return null;
    }

    public String preprocessFind(String text, String url, final boolean allowDeep) {
        if (text != null) {
            text = text.replaceAll("/\\s*Sharecode\\[\\?\\]:\\s*/", "/");
            text = text.replaceAll("\\s*Sharecode\\[\\?\\]:\\s*", "");
            text = text.replaceAll("/?\\s*Sharecode:\\s*/?", "/");
        }
        return text;
    }

    public void crawl(final List<CrawledLink> possibleCryptedLinks) {
        crawl(getValidLinkCrawlerGeneration(), possibleCryptedLinks);
    }

    protected void crawl(final LinkCrawlerGeneration generation, final List<CrawledLink> possibleCryptedLinks) {
        if (possibleCryptedLinks != null && possibleCryptedLinks.size() > 0) {
            final LinkCrawlerTask task;
            if ((task = checkStartNotify(generation, "crawlLinks")) != null) {
                try {
                    if (insideCrawlerPlugin()) {
                        /*
                         * direct decrypt this link because we are already inside a LinkCrawlerThread and this avoids deadlocks on plugin
                         * waiting for linkcrawler results
                         */
                        distribute(generation, possibleCryptedLinks);
                    } else {
                        /*
                         * enqueue this cryptedLink for decrypting
                         */
                        final LinkCrawlerTask innerTask;
                        if ((innerTask = checkStartNotify(generation, task.getTaskID() + "|crawlLinksPool")) != null) {
                            threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation, innerTask) {
                                @Override
                                public long getAverageRuntime() {
                                    final Long ret = getDefaultAverageRuntime();
                                    if (ret != null) {
                                        return ret;
                                    } else {
                                        return super.getAverageRuntime();
                                    }
                                }

                                @Override
                                void crawling() {
                                    distribute(generation, possibleCryptedLinks);
                                }
                            });
                        }
                    }
                } finally {
                    checkFinishNotify(task);
                }
            }
        }
    }

    public static boolean insideCrawlerPlugin() {
        if (Thread.currentThread() instanceof LinkCrawlerThread) {
            final Object owner = ((LinkCrawlerThread) Thread.currentThread()).getCurrentOwner();
            if (owner != null && owner instanceof PluginForDecrypt) {
                return true;
            }
        }
        return false;
    }

    public static boolean insideHosterPlugin() {
        if (Thread.currentThread() instanceof LinkCrawlerThread) {
            final Object owner = ((LinkCrawlerThread) Thread.currentThread()).getCurrentOwner();
            if (owner != null && owner instanceof PluginForHost) {
                return true;
            }
        }
        return false;
    }

    /*
     * check if all known crawlers are done and notify all waiting listener + cleanup DuplicateFinder
     */
    protected static void checkFinishNotify(final LinkCrawlerTask task) {
        if (task != null && task.invalidate()) {
            final LinkCrawler linkCrawler = task.getCrawler();
            /* this LinkCrawler instance stopped, notify static counter */
            final boolean finished;
            final boolean stopped;
            synchronized (CRAWLER) {
                linkCrawler.tasks.remove(task);
                final boolean crawling = linkCrawler.tasks.size() > 0;
                if (crawling) {
                    if (DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
                        System.out.println("LinkCrawler:checkFinishNotify:" + linkCrawler + "|Task:(" + task.getTaskID() + ")|Crawling:" + linkCrawler.tasks.size());
                    }
                    return;
                } else {
                    stopped = CRAWLER.remove(linkCrawler);
                    finished = CRAWLER.size() == 0;
                }
                if (DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
                    System.out.println("LinkCrawler:checkFinishNotify:" + linkCrawler + "|Task:(" + task.getTaskID() + ")|Stopped:" + stopped + "|Finished:" + finished);
                }
            }
            if (stopped) {
                synchronized (WAIT) {
                    WAIT.notifyAll();
                }
                if (linkCrawler.getParent() == null) {
                    linkCrawler.cleanup();
                }
                EVENTSENDER.fireEvent(new LinkCrawlerEvent(linkCrawler, LinkCrawlerEvent.Type.STOPPED));
                linkCrawler.crawlerStopped();
            }
            if (finished) {
                synchronized (WAIT) {
                    WAIT.notifyAll();
                }
                linkCrawler.cleanup();
                EVENTSENDER.fireEvent(new LinkCrawlerEvent(linkCrawler, LinkCrawlerEvent.Type.FINISHED));
                linkCrawler.crawlerFinished();
            }
        }
    }

    protected void cleanup() {
        /*
         * all tasks are done , we can now cleanup our duplicateFinder
         */
        synchronized (duplicateFinderContainer) {
            duplicateFinderContainer.clear();
        }
        synchronized (duplicateFinderCrawler) {
            duplicateFinderCrawler.clear();
        }
        synchronized (duplicateFinderFinal) {
            duplicateFinderFinal.clear();
        }
        synchronized (duplicateFinderDeep) {
            duplicateFinderDeep.clear();
        }
        synchronized (loopPreventionEmbedded) {
            loopPreventionEmbedded.clear();
        }
    }

    protected void crawlerStopped() {
        synchronized (CRAWLER_CACHE) {
            CRAWLER_CACHE.remove(this);
        }
    }

    protected void crawlerStarted() {
    }

    protected void crawlerFinished() {
    }

    protected LinkCrawlerTask checkStartNotify(final LinkCrawlerGeneration generation, final String taskID) {
        if (generation != null && generation.isValid()) {
            final LinkCrawlerTask task = new LinkCrawlerTask(this, generation, taskID);
            boolean event;
            synchronized (CRAWLER) {
                final LinkCrawler linkCrawler = task.getCrawler();
                final boolean start = linkCrawler.tasks.size() == 0;
                linkCrawler.tasks.add(task);
                event = CRAWLER.add(linkCrawler) && start;
                if (DebugMode.TRUE_IN_IDE_ELSE_FALSE) {
                    System.out.println("LinkCrawler:checkStartNotify:" + linkCrawler + "|Task:(" + task.getTaskID() + ")|Start:" + start + "|Crawler:" + event + "|Crawling:" + linkCrawler.tasks.size());
                }
            }
            try {
                if (event) {
                    EVENTSENDER.fireEvent(new LinkCrawlerEvent(task.getCrawler(), LinkCrawlerEvent.Type.STARTED));
                    task.getCrawler().crawlerStarted();
                }
            } catch (RuntimeException e) {
                checkFinishNotify(task);
                throw e;
            }
            return task;
        }
        return null;
    }

    protected URLConnectionAdapter openCrawlDeeperConnection(final LinkCrawlerGeneration generation, final LogInterface logger, final LinkCrawlerRule matchingRule, Browser br, CrawledLink source) throws Exception {
        final LazyCrawlerPlugin lazyC = getDeepCrawlingPlugin();
        if (lazyC == null) {
            throw new UpdateRequiredClassNotFoundException("could not find 'LinkCrawlerDeepHelper' crawler plugin");
        }
        final PluginForDecrypt wplg = lazyC.newInstance(getPluginClassLoaderChild());
        final AtomicReference<LinkCrawler> nextLinkCrawler = new AtomicReference<LinkCrawler>(this);
        wplg.setBrowser(br);
        wplg.setLogger(logger);
        wplg.init();
        LogInterface oldLogger = null;
        boolean oldVerbose = false;
        boolean oldDebug = false;
        /* now we run the plugin and let it find some links */
        final LinkCrawlerThread lct = getCurrentLinkCrawlerThread();
        Object owner = null;
        LinkCrawler previousCrawler = null;
        try {
            if (lct != null) {
                /* mark thread to be used by decrypter plugin */
                owner = lct.getCurrentOwner();
                lct.setCurrentOwner(wplg);
                previousCrawler = lct.getCurrentLinkCrawler();
                lct.setCurrentLinkCrawler(this);
                /* save old logger/states */
                oldLogger = lct.getLogger();
                oldDebug = lct.isDebug();
                oldVerbose = lct.isVerbose();
                /* set new logger and set verbose/debug true */
                lct.setLogger(logger);
                lct.setVerbose(true);
                lct.setDebug(true);
            }
            final long startTime = System.currentTimeMillis();
            try {
                wplg.setCrawler(this);
                wplg.setLinkCrawlerGeneration(generation);
                final LinkCrawler pluginNextLinkCrawler = wplg.getCustomNextCrawler();
                if (pluginNextLinkCrawler != null) {
                    nextLinkCrawler.set(pluginNextLinkCrawler);
                }
                return ((LinkCrawlerDeepHelperInterface) wplg).openConnection(matchingRule, br, source);
            } finally {
                wplg.clean();
                wplg.setLinkCrawlerGeneration(null);
                wplg.setCurrentLink(null);
                final long endTime = System.currentTimeMillis() - startTime;
                lazyC.updateCrawlRuntime(endTime);
            }
        } finally {
            if (lct != null) {
                /* reset thread to last known used state */
                lct.setCurrentOwner(owner);
                lct.setCurrentLinkCrawler(previousCrawler);
                lct.setLogger(oldLogger);
                lct.setVerbose(oldVerbose);
                lct.setDebug(oldDebug);
            }
        }
    }

    protected boolean isCrawledLinkDuplicated(Map<String, Object> map, CrawledLink link) {
        final String url = link.getURL();
        final String urlDecodedURL = Encoding.urlDecode(url, false);
        final String value;
        if (StringUtils.equals(url, urlDecodedURL)) {
            value = url;
        } else {
            value = urlDecodedURL;
        }
        synchronized (map) {
            if (map.containsKey(value)) {
                return true;
            } else {
                map.put(value, this);
                return false;
            }
        }
    }

    public DownloadLink createDirectHTTPDownloadLink(final Request sourceRequest, URLConnectionAdapter con) {
        final Request request = con.getRequest();
        Request redirectOrigin = request.getRedirectOrigin();
        while (redirectOrigin != null) {
            final Request nextRedirectOrigin = redirectOrigin.getRedirectOrigin();
            if (nextRedirectOrigin != null) {
                redirectOrigin = nextRedirectOrigin;
            } else {
                break;
            }
        }
        final String startURL;
        if (request instanceof PostRequest) {
            startURL = request.getURL().toString();
        } else if (sourceRequest != null) {
            // previous URL is leading/redirecting to this download, so let's use this URL instead
            // for example the current URL might expire
            startURL = sourceRequest.getURL().toString();
        } else if (redirectOrigin != null) {
            startURL = redirectOrigin.getURL().toString();
        } else {
            startURL = request.getURL().toString();
        }
        final DownloadLink link = new DownloadLink(null, null, "DirectHTTP", "directhttp://" + startURL, true);
        final String cookie = con.getRequestProperty("Cookie");
        if (StringUtils.isNotEmpty(cookie)) {
            link.setProperty(DirectHTTP.PROPERTY_COOKIES, cookie);
        }
        final long contentLength = con.getCompleteContentLength();
        if (contentLength > 0) {
            link.setVerifiedFileSize(contentLength);
        }
        {
            boolean allowFileExtensionCorrection = true;
            String fileName = null;
            final DispositionHeader dispositionHeader = Plugin.parseDispositionHeader(con);
            if (dispositionHeader != null && StringUtils.isNotEmpty(dispositionHeader.getFilename())) {
                // trust given filename extension via Content-Disposition header
                allowFileExtensionCorrection = false;
                fileName = dispositionHeader.getFilename();
                if (dispositionHeader.getEncoding() == null) {
                    try {
                        fileName = SimpleFTP.BestEncodingGuessingURLDecode(dispositionHeader.getFilename());
                    } catch (final IllegalArgumentException ignore) {
                    } catch (final UnsupportedEncodingException ignore) {
                    } catch (final IOException ignore) {
                    }
                }
            }
            if (StringUtils.isEmpty(fileName)) {
                fileName = Plugin.extractFileNameFromURL(con.getRequest().getUrl());
                if (StringUtils.isEmpty(fileName)) {
                    fileName = link.getName();
                }
            }
            if (fileName != null) {
                final String ext = Plugin.getExtensionFromMimeTypeStatic(con.getContentType());
                if (ext != null) {
                    if (fileName.indexOf(".") < 0) {
                        fileName = fileName + "." + ext;
                    } else if (allowFileExtensionCorrection) {
                        fileName = Plugin.getCorrectOrApplyFileNameExtension(fileName, "." + ext);
                    }
                }
                link.setFinalFileName(fileName);
                /* save filename in property so we can restore in reset case */
                link.setProperty("fixName", fileName);
            }
        }
        link.setAvailable(true);
        final String requestRef = request.getHeaders().getValue(HTTPConstants.HEADER_REQUEST_REFERER);
        if (requestRef != null && !StringUtils.equals(requestRef, request.getURL().toString())) {
            link.setProperty(PROPERTY_AUTO_REFERER, requestRef);
        }
        if (request instanceof PostRequest) {
            final String postString = ((PostRequest) request).getPostDataString();
            if (postString != null) {
                link.setProperty("post", postString);
            }
        }
        return link;
    }

    public CrawledLink createDirectHTTPCrawledLink(CrawledLink source, Request sourceRequest, URLConnectionAdapter con) {
        return crawledLinkFactorybyDownloadLink(createDirectHTTPDownloadLink(sourceRequest, con));
    }

    protected static interface DeeperOrMatchingRuleModifier extends CrawledLinkModifier {
        public CrawledLinkModifier getSourceCrawledLinkModifier();
    }

    protected void crawlDeeperOrMatchingRule(final LinkCrawlerGeneration generation, final CrawledLink source) {
        final CrawledLinkModifier sourceLinkModifier;
        if (source.getCustomCrawledLinkModifier() instanceof DeeperOrMatchingRuleModifier) {
            CrawledLinkModifier modifier = source.getCustomCrawledLinkModifier();
            while (modifier instanceof DeeperOrMatchingRuleModifier) {
                modifier = ((DeeperOrMatchingRuleModifier) modifier).getSourceCrawledLinkModifier();
            }
            sourceLinkModifier = modifier;
        } else {
            sourceLinkModifier = source.getCustomCrawledLinkModifier();
        }
        source.setCustomCrawledLinkModifier(null);
        source.setBrokenCrawlerHandler(null);
        if (source == null || source.getURL() == null) {
            return;
        } else if (isCrawledLinkDuplicated(duplicateFinderDeep, source)) {
            onCrawledLinkDuplicate(source, DUPLICATE.DEEP);
            return;
        } else if (this.isCrawledLinkFiltered(source)) {
            return;
        }
        final LinkCrawlerRule matchingRule = source.getMatchingRule();
        final List<CrawledLinkModifier> additionalModifier = new ArrayList<CrawledLinkModifier>();
        final CrawledLinkModifier lm = new DeeperOrMatchingRuleModifier() {
            public CrawledLinkModifier getSourceCrawledLinkModifier() {
                return sourceLinkModifier;
            }

            public boolean modifyCrawledLink(CrawledLink link) {
                final boolean setContainerURL = link.getDownloadLink() != null && link.getDownloadLink().getContainerUrl() == null;
                boolean ret = false;
                if (sourceLinkModifier != null) {
                    if (sourceLinkModifier.modifyCrawledLink(link)) {
                        ret = true;
                    }
                }
                if (setContainerURL) {
                    link.getDownloadLink().setContainerUrl(source.getURL());
                    ret = true;
                }
                for (final CrawledLinkModifier modifier : additionalModifier) {
                    if (modifier.modifyCrawledLink(link)) {
                        ret = true;
                    }
                }
                return ret;
            }
        };
        final LinkCrawlerTask task;
        if ((task = checkStartNotify(generation, "crawlDeeperOrMatchingRule:" + source.getURL())) != null) {
            try {
                Browser br = null;
                final LogInterface logger;
                if (matchingRule != null && matchingRule.isLogging()) {
                    logger = LogController.getFastPluginLogger("LinkCrawlerRule." + matchingRule.getId());
                } else {
                    logger = LogController.getFastPluginLogger("LinkCrawlerDeep." + CrossSystem.alleviatePathParts(source.getHost()));
                }
                try {
                    processedLinksCounter.incrementAndGet();
                    if (StringUtils.startsWithCaseInsensitive(source.getURL(), "file:/")) {
                        // file:/ -> not authority -> all fine
                        // file://xy/ -> xy authority -> java.lang.IllegalArgumentException: URI has an authority component
                        // file:/// -> empty authority -> all fine
                        final String currentURI = source.getURL().replaceFirst("file:///?", "file:///");
                        final File file = new File(new URI(currentURI));
                        if (file.exists() && file.isFile()) {
                            final int limit = CONFIG.getDeepDecryptFileSizeLimit();
                            final int readLimit = limit == -1 ? -1 : Math.max(1 * 1024 * 1024, limit);
                            final String fileContent = new String(IO.readFile(file, readLimit), "UTF-8");
                            final List<CrawledLink> fileContentLinks = find(generation, source, fileContent, null, false, false);
                            if (fileContentLinks != null) {
                                final String[] sourceURLs = getAndClearSourceURLs(source);
                                final boolean singleDest = fileContentLinks.size() == 1;
                                for (final CrawledLink fileContentLink : fileContentLinks) {
                                    forwardCrawledLinkInfos(source, fileContentLink, lm, sourceURLs, singleDest);
                                }
                                crawl(generation, fileContentLinks);
                            }
                        }
                    } else {
                        br = new Browser();
                        br.setLogger(logger);
                        if (matchingRule != null && matchingRule.isLogging()) {
                            br.setVerbose(true);
                            br.setDebug(true);
                        }
                        final URLConnectionAdapter connection = openCrawlDeeperConnection(generation, logger, matchingRule, br, source);
                        final CrawledLink deeperSource;
                        final String[] sourceURLs;
                        if (StringUtils.equals(connection.getRequest().getUrl(), source.getURL())) {
                            deeperSource = source;
                            sourceURLs = getAndClearSourceURLs(source);
                        } else {
                            deeperSource = crawledLinkFactorybyURL(connection.getRequest().getUrl());
                            forwardCrawledLinkInfos(source, deeperSource, lm, getAndClearSourceURLs(source), true);
                            sourceURLs = getAndClearSourceURLs(deeperSource);
                        }
                        if (matchingRule != null && LinkCrawlerRule.RULE.FOLLOWREDIRECT.equals(matchingRule.getRule())) {
                            br.disconnect();
                            final ArrayList<CrawledLink> followRedirectLinks = new ArrayList<CrawledLink>();
                            followRedirectLinks.add(deeperSource);
                            crawl(generation, followRedirectLinks);
                        } else {
                            final LinkCrawlerDeepInspector lDeepInspector = getDeepInspector();
                            final List<CrawledLink> inspectedLinks;
                            try {
                                inspectedLinks = lDeepInspector.deepInspect(this, generation, br, connection, deeperSource);
                            } catch (final IOException e) {
                                if (logger != null) {
                                    logger.log(e);
                                }
                                throw e;
                            }
                            /*
                             * downloadable content, we use directhttp and distribute the url
                             */
                            if (inspectedLinks != null) {
                                if (inspectedLinks.size() >= 0) {
                                    final boolean singleDest = inspectedLinks.size() == 1;
                                    for (final CrawledLink possibleCryptedLink : inspectedLinks) {
                                        forwardCrawledLinkInfos(deeperSource, possibleCryptedLink, lm, sourceURLs, singleDest);
                                    }
                                    crawl(generation, inspectedLinks);
                                }
                            } else {
                                final String finalPackageName;
                                if (matchingRule != null && matchingRule._getPackageNamePattern() != null) {
                                    final String packageName = br.getRegex(matchingRule._getPackageNamePattern()).getMatch(0);
                                    if (StringUtils.isNotEmpty(packageName)) {
                                        finalPackageName = Encoding.htmlDecode(packageName.trim());
                                    } else {
                                        finalPackageName = null;
                                    }
                                } else {
                                    finalPackageName = null;
                                }
                                /* try to load the webpage and find links on it */
                                if (matchingRule != null && LinkCrawlerRule.RULE.SUBMITFORM.equals(matchingRule.getRule())) {
                                    final Form[] forms = br.getForms();
                                    final Pattern formPattern = matchingRule._getFormPattern();
                                    final ArrayList<CrawledLink> formLinks = new ArrayList<CrawledLink>();
                                    if (forms != null && formPattern != null) {
                                        for (final Form form : forms) {
                                            if ((StringUtils.isNotEmpty(form.getAction()) && formPattern.matcher(form.getAction()).matches()) || form.containsHTML(formPattern.pattern())) {
                                                final Browser clone = br.cloneBrowser();
                                                clone.setFollowRedirects(false);
                                                final Request request = clone.createFormRequest(form);
                                                final URLConnectionAdapter con = clone.openRequestConnection(request);
                                                final String redirect = con.getRequest().getLocation();
                                                if (redirect != null) {
                                                    clone.followConnection();
                                                    formLinks.add(crawledLinkFactorybyURL(redirect));
                                                } else if (lDeepInspector.looksLikeDownloadableContent(con)) {
                                                    con.disconnect();
                                                    final CrawledLink formLink = createDirectHTTPCrawledLink(deeperSource, request, con);
                                                    if (formLink != null) {
                                                        formLinks.add(formLink);
                                                    }
                                                } else {
                                                    clone.followConnection();
                                                }
                                            }
                                        }
                                    }
                                    if (formLinks != null && formLinks.size() > 0) {
                                        final boolean singleDest = formLinks.size() == 1;
                                        for (final CrawledLink formLink : formLinks) {
                                            forwardCrawledLinkInfos(deeperSource, formLink, lm, sourceURLs, singleDest);
                                            PackageInfo dpi = formLink.getDesiredPackageInfo();
                                            if (dpi == null) {
                                                dpi = new PackageInfo();
                                            }
                                            dpi.setName(finalPackageName);
                                            formLink.setDesiredPackageInfo(dpi);
                                        }
                                        crawl(generation, formLinks);
                                    }
                                } else {
                                    // We need browser currentURL and not sourceURL, because of possible redirects will change domain and or
                                    // relative path.
                                    boolean isDeepDecrypt = source.isCrawlDeep() || (matchingRule != null && LinkCrawlerRule.RULE.DEEPDECRYPT.equals(matchingRule.getRule()));
                                    final Request request = br.getRequest();
                                    final String brURL;
                                    if (request.getAuthentication() == null) {
                                        brURL = request.getUrl();
                                    } else {
                                        brURL = request.getAuthentication().getURLWithUserInfo(request.getURL());
                                    }
                                    List<CrawledLink> possibleCryptedLinks = find(generation, source, brURL, null, false, false);
                                    if (possibleCryptedLinks != null) {
                                        final boolean singleDest = possibleCryptedLinks.size() == 1;
                                        for (final CrawledLink possibleCryptedLink : possibleCryptedLinks) {
                                            forwardCrawledLinkInfos(deeperSource, possibleCryptedLink, lm, sourceURLs, singleDest);
                                            PackageInfo dpi = possibleCryptedLink.getDesiredPackageInfo();
                                            if (dpi == null) {
                                                dpi = new PackageInfo();
                                            }
                                            dpi.setName(finalPackageName);
                                            possibleCryptedLink.setDesiredPackageInfo(dpi);
                                        }
                                        final CrawledLink deepLink;
                                        if (possibleCryptedLinks.size() == 1) {
                                            deepLink = possibleCryptedLinks.get(0);
                                        } else if (isDeepDecrypt) {
                                            CrawledLink deep = null;
                                            for (final CrawledLink possibleCryptedLink : possibleCryptedLinks) {
                                                if (StringUtils.equalsIgnoreCase(possibleCryptedLink.getURL(), source.getURL())) {
                                                    deep = possibleCryptedLink;
                                                    break;
                                                }
                                            }
                                            deepLink = deep;
                                        } else {
                                            deepLink = null;
                                        }
                                        if (deepLink != null) {
                                            final String finalBaseUrl = new Regex(brURL, "(https?://.*?)(\\?|$)").getMatch(0);
                                            final String crawlContent;
                                            final boolean deepPatternContent;
                                            if (matchingRule != null && matchingRule._getDeepPattern() != null) {
                                                deepPatternContent = true;
                                                final String[][] matches = new Regex(request.getHtmlCode(), matchingRule._getDeepPattern()).getMatches();
                                                if (matches != null) {
                                                    final HashSet<String> dups = new HashSet<String>();
                                                    final StringBuilder sb = new StringBuilder();
                                                    for (final String matcharray[] : matches) {
                                                        for (final String match : matcharray) {
                                                            if (StringUtils.isNotEmpty(match) && !brURL.equals(match) && dups.add(match)) {
                                                                if (sb.length() > 0) {
                                                                    sb.append("\r\n");
                                                                }
                                                                sb.append(match);
                                                                if (match.matches("^[^<>\"]+$")) {
                                                                    try {
                                                                        final String url = br.getURL(match).toString();
                                                                        if (dups.add(url)) {
                                                                            sb.append("\r\n").append(url);
                                                                        }
                                                                    } catch (final Throwable e) {
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                    crawlContent = sb.toString();
                                                } else {
                                                    crawlContent = null;
                                                }
                                            } else {
                                                deepPatternContent = false;
                                                crawlContent = request.getHtmlCode();
                                            }
                                            if (matchingRule != null && matchingRule._getPasswordPattern() != null) {
                                                final String[][] matches = new Regex(request.getHtmlCode(), matchingRule._getPasswordPattern()).getMatches();
                                                if (matches != null) {
                                                    final HashSet<String> passwords = new HashSet<String>();
                                                    for (final String matcharray[] : matches) {
                                                        for (final String match : matcharray) {
                                                            if (StringUtils.isNotEmpty(match)) {
                                                                passwords.add(match);
                                                            }
                                                        }
                                                    }
                                                    if (passwords.size() > 0) {
                                                        additionalModifier.add(new CrawledLinkModifier() {
                                                            @Override
                                                            public boolean modifyCrawledLink(CrawledLink link) {
                                                                for (final String password : passwords) {
                                                                    link.getArchiveInfo().addExtractionPassword(password);
                                                                }
                                                                return true;
                                                            }
                                                        });
                                                    }
                                                }
                                            }
                                            if (crawlContent != null) {
                                                final List<CrawledLink> possibleDeepCryptedLinks = find(generation, source, crawlContent, finalBaseUrl, false, false);
                                                if (possibleDeepCryptedLinks != null && possibleDeepCryptedLinks.size() > 0) {
                                                    final boolean singleDeepCryptedDest = possibleDeepCryptedLinks.size() == 1;
                                                    for (final CrawledLink possibleDeepCryptedLink : possibleDeepCryptedLinks) {
                                                        forwardCrawledLinkInfos(deeperSource, possibleDeepCryptedLink, lm, sourceURLs, singleDeepCryptedDest);
                                                        PackageInfo dpi = possibleDeepCryptedLink.getDesiredPackageInfo();
                                                        if (dpi == null) {
                                                            dpi = new PackageInfo();
                                                        }
                                                        dpi.setName(finalPackageName);
                                                        possibleDeepCryptedLink.setDesiredPackageInfo(dpi);
                                                    }
                                                    if (deepPatternContent && StringUtils.startsWithCaseInsensitive(source.getURL(), deepLink.getURL())) {
                                                        /*
                                                         * deepLink is our source and a matching deepPattern, crawl the links directly and
                                                         * don't wait for UnknownCrawledLinkHandler
                                                         */
                                                        possibleCryptedLinks = null;
                                                        crawl(generation, possibleDeepCryptedLinks);
                                                    } else {
                                                        /* first check if the url itself can be handled */
                                                        deepLink.setUnknownHandler(new UnknownCrawledLinkHandler() {
                                                            @Override
                                                            public void unhandledCrawledLink(CrawledLink link, LinkCrawler lc) {
                                                                /* unhandled url, lets parse the content on it */
                                                                lc.crawl(generation, possibleDeepCryptedLinks);
                                                            }
                                                        });
                                                    }
                                                }
                                            }
                                        }
                                        if (possibleCryptedLinks != null) {
                                            crawl(generation, possibleCryptedLinks);
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable e) {
                    LogController.CL().log(e);
                    if (logger != null) {
                        logger.log(e);
                    }
                } finally {
                    if (br != null) {
                        br.disconnect();
                    }
                    if (logger != null && logger instanceof ClosableLogInterface) {
                        ((ClosableLogInterface) logger).close();
                    }
                }
            } finally {
                checkFinishNotify(task);
            }
        }
    }

    protected boolean distributeCrawledLink(CrawledLink crawledLink) {
        return crawledLink != null && crawledLink.gethPlugin() == null;
    }

    public boolean canHandle(final LazyPlugin<? extends Plugin> lazyPlugin, final String url, final CrawledLink link) {
        try {
            if (lazyPlugin.canHandle(url)) {
                final Plugin plugin = lazyPlugin.getPrototype(getPluginClassLoaderChild(), false);
                return plugin != null && plugin.canHandle(url);
            }
        } catch (Throwable e) {
            LogController.CL().log(e);
        }
        return false;
    }

    protected DISTRIBUTE distributePluginForHost(final LazyHostPlugin pluginForHost, final LinkCrawlerGeneration generation, final String url, final CrawledLink link) {
        try {
            if (canHandle(pluginForHost, url, link)) {
                if (isBlacklisted(pluginForHost)) {
                    if (LogController.getInstance().isDebugMode()) {
                        LogController.CL().info("blacklisted! " + pluginForHost);
                    }
                    return DISTRIBUTE.BLACKLISTED;
                }
                if (insideCrawlerPlugin()) {
                    if (!generation.isValid()) {
                        /* LinkCrawler got aborted! */
                        return DISTRIBUTE.STOP;
                    }
                    processHostPlugin(generation, pluginForHost, link);
                } else {
                    final LinkCrawlerTask innerTask;
                    if ((innerTask = checkStartNotify(generation, "distributePluginForHost:" + pluginForHost + "|" + link.getURL())) != null) {
                        threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation, innerTask) {
                            @Override
                            public long getAverageRuntime() {
                                final Long ret = getDefaultAverageRuntime();
                                if (ret != null) {
                                    return ret;
                                } else {
                                    return pluginForHost.getAverageParseRuntime();
                                }
                            }

                            @Override
                            void crawling() {
                                processHostPlugin(generation, pluginForHost, link);
                            }
                        });
                    } else {
                        /* LinkCrawler got aborted! */
                        return DISTRIBUTE.STOP;
                    }
                }
                return DISTRIBUTE.NEXT;
            }
        } catch (final Throwable e) {
            LogController.CL().log(e);
        }
        return DISTRIBUTE.CONTINUE;
    }

    /**
     * break PluginForDecrypt loop when PluginForDecrypt and PluginForHost listen on same urls
     *
     * @param pDecrypt
     * @param link
     * @return
     */
    public boolean breakPluginForDecryptLoop(final LazyCrawlerPlugin pDecrypt, final CrawledLink link) {
        final boolean canHandle = canHandle(pDecrypt, link.getURL(), link.getSourceLink());
        if (canHandle) {
            if (!AvailableLinkState.UNKNOWN.equals(link.getLinkState())) {
                return true;
            }
            CrawledLink source = link.getSourceLink();
            final HashSet<String> dontRetry = new HashSet<String>();
            while (source != null) {
                if (source.getCryptedLink() != null) {
                    if (StringUtils.equals(link.getURL(), source.getURL())) {
                        final LazyCrawlerPlugin lazyC = source.getCryptedLink().getLazyC();
                        dontRetry.add(lazyC.getDisplayName() + lazyC.getClassName());
                    }
                }
                source = source.getSourceLink();
            }
            final boolean ret = dontRetry.contains(pDecrypt.getDisplayName() + pDecrypt.getClassName());
            return ret;
        } else {
            return false;
        }
    }

    protected DISTRIBUTE distributePluginForDecrypt(final LazyCrawlerPlugin pDecrypt, final LinkCrawlerGeneration generation, final String url, final CrawledLink link) {
        try {
            if (canHandle(pDecrypt, url, link)) {
                if (isBlacklisted(pDecrypt)) {
                    if (LogController.getInstance().isDebugMode()) {
                        LogController.CL().info("blacklisted! " + pDecrypt);
                    }
                    return DISTRIBUTE.BLACKLISTED;
                }
                if (!breakPluginForDecryptLoop(pDecrypt, link)) {
                    final java.util.List<CrawledLink> allPossibleCryptedLinks = getCryptedLinks(pDecrypt, link, link.getCustomCrawledLinkModifier());
                    if (allPossibleCryptedLinks != null) {
                        if (insideCrawlerPlugin()) {
                            /*
                             * direct decrypt this link because we are already inside a LinkCrawlerThread and this avoids deadlocks on
                             * plugin waiting for linkcrawler results
                             */
                            for (final CrawledLink decryptThis : allPossibleCryptedLinks) {
                                if (!generation.isValid()) {
                                    /* LinkCrawler got aborted! */
                                    return DISTRIBUTE.STOP;
                                }
                                crawl(generation, pDecrypt, decryptThis);
                            }
                        } else {
                            /*
                             * enqueue these cryptedLinks for decrypting
                             */
                            for (final CrawledLink decryptThis : allPossibleCryptedLinks) {
                                final LinkCrawlerTask innerTask;
                                if ((innerTask = checkStartNotify(generation, "distributePluginForDecrypt:" + pDecrypt + "|" + link.getURL() + "|" + decryptThis.getURL())) != null) {
                                    threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation, innerTask) {
                                        public long getAverageRuntime() {
                                            final Long ret = getDefaultAverageRuntime();
                                            if (ret != null) {
                                                return ret;
                                            } else {
                                                return pDecrypt.getAverageCrawlRuntime();
                                            }
                                        }

                                        @Override
                                        protected LinkCrawlerLock getLinkCrawlerLock() {
                                            return LinkCrawler.this.getLinkCrawlerLock(pDecrypt, decryptThis);
                                        }

                                        @Override
                                        void crawling() {
                                            crawl(generation, pDecrypt, decryptThis);
                                        }
                                    });
                                } else {
                                    /* LinkCrawler got aborted! */
                                    return DISTRIBUTE.STOP;
                                }
                            }
                        }
                    }
                    return DISTRIBUTE.NEXT;
                } else {
                    return DISTRIBUTE.CONTINUE;
                }
            }
        } catch (final Throwable e) {
            LogController.CL().log(e);
        }
        return DISTRIBUTE.CONTINUE;
    }

    protected Boolean distributePluginC(final PluginsC pluginC, final LinkCrawlerGeneration generation, final String url, final CrawledLink link) {
        try {
            if (pluginC.canHandle(url)) {
                final CrawledLinkModifier originalModifier = link.getCustomCrawledLinkModifier();
                final CrawledLinkModifier lm;
                if (pluginC.hideLinks()) {
                    final ArrayList<CrawledLinkModifier> modifiers = new ArrayList<CrawledLinkModifier>();
                    if (originalModifier != null) {
                        modifiers.add(originalModifier);
                    }
                    modifiers.add(new CrawledLinkModifier() {
                        @Override
                        public boolean modifyCrawledLink(CrawledLink link) {
                            /* we hide the links */
                            final DownloadLink dl = link.getDownloadLink();
                            if (dl != null) {
                                dl.setUrlProtection(UrlProtection.PROTECTED_CONTAINER);
                                return true;
                            }
                            return false;
                        }
                    });
                    lm = new CrawledLinkModifier() {
                        @Override
                        public boolean modifyCrawledLink(CrawledLink link) {
                            boolean ret = false;
                            for (CrawledLinkModifier mod : modifiers) {
                                if (mod.modifyCrawledLink(link)) {
                                    ret = true;
                                }
                            }
                            return ret;
                        }
                    };
                } else {
                    lm = originalModifier;
                }
                final java.util.List<CrawledLink> allPossibleCryptedLinks = getCrawledLinks(pluginC.getSupportedLinks(), link, lm);
                if (allPossibleCryptedLinks != null) {
                    if (insideCrawlerPlugin()) {
                        /*
                         * direct decrypt this link because we are already inside a LinkCrawlerThread and this avoids deadlocks on plugin
                         * waiting for linkcrawler results
                         */
                        for (final CrawledLink decryptThis : allPossibleCryptedLinks) {
                            if (!generation.isValid()) {
                                /* LinkCrawler got aborted! */
                                return false;
                            }
                            container(generation, pluginC, decryptThis);
                        }
                    } else {
                        /*
                         * enqueue these cryptedLinks for decrypting
                         */
                        for (final CrawledLink decryptThis : allPossibleCryptedLinks) {
                            final LinkCrawlerTask innerTask;
                            if ((innerTask = checkStartNotify(generation, "distributePluginC:" + pluginC.getName() + "|" + link.getURL() + "|" + decryptThis.getURL())) != null) {
                                threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation, innerTask) {
                                    @Override
                                    public long getAverageRuntime() {
                                        final Long ret = getDefaultAverageRuntime();
                                        if (ret != null) {
                                            return ret;
                                        } else {
                                            return super.getAverageRuntime();
                                        }
                                    }

                                    @Override
                                    void crawling() {
                                        container(generation, pluginC, decryptThis);
                                    }
                                });
                            } else {
                                /* LinkCrawler got aborted! */
                                return false;
                            }
                        }
                    }
                }
                return true;
            }
        } catch (final Throwable e) {
            LogController.CL().log(e);
        }
        return null;
    }

    protected DISTRIBUTE rewrite(final LinkCrawlerGeneration generation, final String url, final CrawledLink source) {
        try {
            final LinkCrawlerRule rule = getFirstMatchingRule(source, url, LinkCrawlerRule.RULE.REWRITE);
            if (rule != null && rule.getRewriteReplaceWith() != null) {
                source.setMatchingRule(rule);
                final String newURL = url.replaceAll(rule.getPattern(), rule.getRewriteReplaceWith());
                final LinkCrawlerTask innerTask;
                if (!url.equals(newURL)) {
                    final CrawledLinkModifier lm = source.getCustomCrawledLinkModifier();
                    source.setCustomCrawledLinkModifier(null);
                    source.setBrokenCrawlerHandler(null);
                    final CrawledLink rewritten = crawledLinkFactorybyURL(newURL);
                    forwardCrawledLinkInfos(source, rewritten, lm, getAndClearSourceURLs(source), true);
                    if (insideCrawlerPlugin()) {
                        if (!generation.isValid()) {
                            /* LinkCrawler got aborted! */
                            return DISTRIBUTE.STOP;
                        }
                        distribute(generation, rewritten);
                        return DISTRIBUTE.NEXT;
                    } else if ((innerTask = checkStartNotify(generation, "rewritePool:" + source.getURL() + "|" + rewritten.getURL())) != null) {
                        threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation, innerTask) {
                            @Override
                            public long getAverageRuntime() {
                                final Long ret = getDefaultAverageRuntime();
                                if (ret != null) {
                                    return ret;
                                } else {
                                    return super.getAverageRuntime();
                                }
                            }

                            @Override
                            void crawling() {
                                distribute(generation, rewritten);
                            }
                        });
                        return DISTRIBUTE.NEXT;
                    } else {
                        return DISTRIBUTE.STOP;
                    }
                }
            }
        } catch (Throwable e) {
            LogController.CL().log(e);
        }
        return DISTRIBUTE.CONTINUE;
    }

    protected Boolean distributeDeeperOrMatchingRule(final LinkCrawlerGeneration generation, final String url, final CrawledLink link) {
        try {
            LinkCrawlerRule rule = null;
            /* do not change order, it is important to check redirect first */
            if ((rule = getFirstMatchingRule(link, url, LinkCrawlerRule.RULE.SUBMITFORM, LinkCrawlerRule.RULE.FOLLOWREDIRECT, LinkCrawlerRule.RULE.DEEPDECRYPT)) != null || link.isCrawlDeep()) {
                if (rule != null) {
                    link.setMatchingRule(rule);
                }
                /* the link is allowed to crawlDeep */
                if (insideCrawlerPlugin()) {
                    if (!generation.isValid()) {
                        /* LinkCrawler got aborted! */
                        return false;
                    }
                    crawlDeeperOrMatchingRule(generation, link);
                } else {
                    final LinkCrawlerTask innerTask;
                    if ((innerTask = checkStartNotify(generation, "distributeDeeperOrMatchingRulePool:" + link.getURL())) != null) {
                        threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation, innerTask) {
                            @Override
                            public long getAverageRuntime() {
                                final Long ret = getDefaultAverageRuntime();
                                if (ret != null) {
                                    return ret;
                                } else {
                                    return super.getAverageRuntime();
                                }
                            }

                            @Override
                            void crawling() {
                                crawlDeeperOrMatchingRule(generation, link);
                            }
                        });
                    } else {
                        return false;
                    }
                }
                return true;
            }
        } catch (final Throwable e) {
            LogController.CL().log(e);
        }
        return null;
    }

    protected void distribute(final LinkCrawlerGeneration generation, CrawledLink... possibleCryptedLinks) {
        if (possibleCryptedLinks != null && possibleCryptedLinks.length > 0) {
            distribute(generation, Arrays.asList(possibleCryptedLinks));
        }
    }

    protected CrawledLink createCopyOf(CrawledLink source) {
        final CrawledLink ret;
        if (source.getDownloadLink() != null) {
            ret = new CrawledLink(source.getDownloadLink());
        } else if (source.getCryptedLink() != null) {
            ret = new CrawledLink(source.getCryptedLink());
        } else {
            ret = new CrawledLink(source.getURL());
        }
        ret.setSourceLink(source);
        if (source.hasCollectingInfo()) {
            ret.setCollectingInfo(source.getCollectingInfo());
        }
        ret.setSourceJob(source.getSourceJob());
        ret.setSourceUrls(source.getSourceUrls());
        ret.setOrigin(source.getOrigin());
        ret.setCrawlDeep(source.isCrawlDeep());
        if (source.hasArchiveInfo()) {
            ret.setArchiveInfo(source.getArchiveInfo());
        }
        ret.setCustomCrawledLinkModifier(source.getCustomCrawledLinkModifier());
        ret.setBrokenCrawlerHandler(source.getBrokenCrawlerHandler());
        ret.setUnknownHandler(source.getUnknownHandler());
        ret.setMatchingFilter(source.getMatchingFilter());
        ret.setMatchingRule(source.getMatchingRule());
        // ret.setCreated(source.getCreated());#set in handleFinalCrawledLink
        ret.setEnabled(source.isEnabled());
        ret.setForcedAutoStartEnabled(source.isForcedAutoStartEnabled());
        ret.setAutoConfirmEnabled(source.isAutoConfirmEnabled());
        ret.setAutoStartEnabled(source.isAutoStartEnabled());
        if (source.isNameSet()) {
            ret.setName(source._getName());
        }
        ret.setDesiredPackageInfo(source.getDesiredPackageInfo());
        ret.setParentNode(source.getParentNode());
        return ret;
    }

    protected boolean distributeFinalCrawledLink(final LinkCrawlerGeneration generation, final CrawledLink crawledLink) {
        if (generation != null && generation.isValid() && crawledLink != null) {
            this.handleFinalCrawledLink(generation, crawledLink);
            return true;
        } else {
            return false;
        }
    }

    protected void distribute(final LinkCrawlerGeneration generation, List<CrawledLink> possibleCryptedLinks) {
        if (possibleCryptedLinks == null || possibleCryptedLinks.size() == 0) {
            return;
        }
        final LinkCrawlerTask task;
        if ((task = checkStartNotify(generation, "distributeLinks")) != null) {
            try {
                mainloop: for (final CrawledLink possibleCryptedLink : possibleCryptedLinks) {
                    if (!generation.isValid()) {
                        /* LinkCrawler got aborted! */
                        return;
                    }
                    mainloopretry: while (true) {
                        final UnknownCrawledLinkHandler unnknownHandler = possibleCryptedLink.getUnknownHandler();
                        possibleCryptedLink.setUnknownHandler(null);
                        if (!distributeCrawledLink(possibleCryptedLink)) {
                            // direct forward, if we already have a final link.
                            distributeFinalCrawledLink(generation, possibleCryptedLink);
                            continue mainloop;
                        }
                        final String url = possibleCryptedLink.getURL();
                        if (url == null) {
                            /* WTF, no URL?! let's continue */
                            continue mainloop;
                        } else {
                            final DISTRIBUTE ret = rewrite(generation, url, possibleCryptedLink);
                            switch (ret) {
                            case STOP:
                                return;
                            case CONTINUE:
                                break;
                            case BLACKLISTED:
                                continue mainloop;
                            case NEXT:
                                handleUnhandledCryptedLink(possibleCryptedLink);
                                continue mainloop;
                            default:
                                LogController.CL().log(new IllegalStateException(ret.name()));
                                break;
                            }
                        }
                        final boolean isDirect = url.startsWith("directhttp://");
                        final boolean isFtp = url.startsWith("ftp://") || url.startsWith("ftpviajd://");
                        final boolean isFile = url.startsWith("file:/");
                        final boolean isHttpJD = url.startsWith("httpviajd://") || url.startsWith("httpsviajd://");
                        if (isFile) {
                            /*
                             * first we will walk through all available container plugins
                             */
                            for (final PluginsC pCon : ContainerPluginController.getInstance().list()) {
                                final Boolean ret = distributePluginC(pCon, generation, url, possibleCryptedLink);
                                if (Boolean.FALSE.equals(ret)) {
                                    return;
                                } else if (Boolean.TRUE.equals(ret)) {
                                    continue mainloop;
                                }
                            }
                        } else if (!isDirect && !isHttpJD) {
                            {
                                /*
                                 * first we will walk through all available decrypter plugins
                                 */
                                final List<LazyCrawlerPlugin> lazyCrawlerPlugins = getSortedLazyCrawlerPlugins();
                                final ListIterator<LazyCrawlerPlugin> it = lazyCrawlerPlugins.listIterator();
                                loop: while (it.hasNext()) {
                                    final LazyCrawlerPlugin pDecrypt = it.next();
                                    final DISTRIBUTE ret = distributePluginForDecrypt(pDecrypt, generation, url, possibleCryptedLink);
                                    switch (ret) {
                                    case STOP:
                                        return;
                                    case CONTINUE:
                                        break;
                                    case BLACKLISTED:
                                        continue mainloop;
                                    case NEXT:
                                        if (it.previousIndex() > lazyCrawlerPlugins.size() / 50) {
                                            resetSortedLazyCrawlerPlugins(lazyCrawlerPlugins);
                                        }
                                        continue mainloop;
                                    default:
                                        LogController.CL().log(new IllegalStateException(ret.name()));
                                        break;
                                    }
                                }
                            }
                            {
                                /* now we will walk through all available hoster plugins */
                                final List<LazyHostPlugin> sortedLazyHostPlugins = getSortedLazyHostPlugins();
                                final ListIterator<LazyHostPlugin> it = sortedLazyHostPlugins.listIterator();
                                loop: while (it.hasNext()) {
                                    final LazyHostPlugin pHost = it.next();
                                    final DISTRIBUTE ret = distributePluginForHost(pHost, generation, url, possibleCryptedLink);
                                    switch (ret) {
                                    case STOP:
                                        return;
                                    case CONTINUE:
                                        break;
                                    case BLACKLISTED:
                                        continue mainloop;
                                    case NEXT:
                                        if (it.previousIndex() > sortedLazyHostPlugins.size() / 50) {
                                            resetSortedLazyHostPlugins(sortedLazyHostPlugins);
                                        }
                                        continue mainloop;
                                    default:
                                        LogController.CL().log(new IllegalStateException(ret.name()));
                                        break;
                                    }
                                }
                            }
                        }
                        if (isFtp) {
                            final LazyHostPlugin ftpPlugin = getGenericFtpPlugin();
                            if (ftpPlugin != null) {
                                /* now we will check for generic ftp links */
                                final DISTRIBUTE ret = distributePluginForHost(ftpPlugin, generation, url, possibleCryptedLink);
                                switch (ret) {
                                case STOP:
                                    return;
                                case CONTINUE:
                                    break;
                                case BLACKLISTED:
                                case NEXT:
                                    continue mainloop;
                                default:
                                    LogController.CL().log(new IllegalStateException(ret.name()));
                                    break;
                                }
                            }
                        } else if (!isFile) {
                            final DirectHTTPPermission directHTTPPermission = getDirectHTTPPermission();
                            final LazyHostPlugin directPlugin = getDirectHTTPPlugin();
                            if (directPlugin != null) {
                                LinkCrawlerRule rule = null;
                                if (isDirect) {
                                    rule = possibleCryptedLink.getMatchingRule();
                                    if (DirectHTTPPermission.ALWAYS.equals(directHTTPPermission) || (DirectHTTPPermission.RULES_ONLY.equals(directHTTPPermission) && (rule != null && LinkCrawlerRule.RULE.DIRECTHTTP.equals(rule.getRule())))) {
                                        /* now we will check for directPlugin links */
                                        final DISTRIBUTE ret = distributePluginForHost(directPlugin, generation, url, possibleCryptedLink);
                                        switch (ret) {
                                        case STOP:
                                            return;
                                        case CONTINUE:
                                            break;
                                        case BLACKLISTED:
                                        case NEXT:
                                            continue mainloop;
                                        default:
                                            LogController.CL().log(new IllegalStateException(ret.name()));
                                            break;
                                        }
                                    } else {
                                        // DirectHTTPPermission.FORBIDDEN
                                        continue mainloop;
                                    }
                                } else if ((rule = getFirstMatchingRule(possibleCryptedLink, url, LinkCrawlerRule.RULE.DIRECTHTTP)) != null) {
                                    if (!DirectHTTPPermission.FORBIDDEN.equals(directHTTPPermission)) {
                                        // no need to check directHTTPPermission as it is ALWAYS or RULES_ONLY
                                        final CrawledLink copy = createCopyOf(possibleCryptedLink);
                                        final CrawledLinkModifier linkModifier = copy.getCustomCrawledLinkModifier();
                                        copy.setCustomCrawledLinkModifier(null);
                                        final DownloadLink link = new DownloadLink(null, null, null, "directhttp://" + url, true);
                                        if (rule != null && rule.getCookies() != null) {
                                            final StringBuilder sb = new StringBuilder();
                                            for (String[] cookie : rule.getCookies()) {
                                                switch (cookie.length) {
                                                case 1:
                                                    sb.append(cookie[0]).append("=;");
                                                    break;
                                                case 2:
                                                    sb.append(cookie[0]).append("=").append(cookie[1]).append(";");
                                                    break;
                                                case 3:
                                                    try {
                                                        if (cookie[2] != null && url.matches(cookie[2])) {
                                                            sb.append(cookie[0]).append("=").append(cookie[1]).append(";");
                                                        }
                                                    } catch (Exception e) {
                                                        LogController.CL().log(e);
                                                    }
                                                    break;
                                                default:
                                                    break;
                                                }
                                            }
                                            link.setProperty(DirectHTTP.PROPERTY_COOKIES, sb.toString());
                                        }
                                        final CrawledLink directHTTP = crawledLinkFactorybyDownloadLink(link);
                                        directHTTP.setMatchingRule(rule);
                                        forwardCrawledLinkInfos(copy, directHTTP, linkModifier, getAndClearSourceURLs(copy), true);
                                        // modify sourceLink because directHTTP arise from possibleCryptedLink(convert to directhttp)
                                        directHTTP.setSourceLink(possibleCryptedLink.getSourceLink());
                                        final DISTRIBUTE ret = distributePluginForHost(directPlugin, generation, directHTTP.getURL(), directHTTP);
                                        switch (ret) {
                                        case STOP:
                                            return;
                                        case CONTINUE:
                                            break;
                                        case BLACKLISTED:
                                        case NEXT:
                                            continue mainloop;
                                        default:
                                            LogController.CL().log(new IllegalStateException(ret.name()));
                                            break;
                                        }
                                    } else {
                                        // DirectHTTPPermission.FORBIDDEN
                                        continue mainloop;
                                    }
                                }
                            }
                            final LazyHostPlugin httpPlugin = getGenericHttpPlugin();
                            if (httpPlugin != null && url.startsWith("http")) {
                                try {
                                    if (canHandle(httpPlugin, url, possibleCryptedLink) && getFirstMatchingRule(possibleCryptedLink, url.replaceFirst("(https?)(viajd)://", "$1://"), LinkCrawlerRule.RULE.SUBMITFORM, LinkCrawlerRule.RULE.FOLLOWREDIRECT, LinkCrawlerRule.RULE.DEEPDECRYPT) == null) {
                                        synchronized (loopPreventionEmbedded) {
                                            if (!loopPreventionEmbedded.containsKey(possibleCryptedLink)) {
                                                final UnknownCrawledLinkHandler unknownLinkHandler = new UnknownCrawledLinkHandler() {
                                                    @Override
                                                    public void unhandledCrawledLink(CrawledLink link, LinkCrawler lc) {
                                                        lc.distribute(generation, Arrays.asList(new CrawledLink[] { possibleCryptedLink }));
                                                    }
                                                };
                                                final DISTRIBUTE ret = distributeEmbeddedLink(generation, url, createCopyOf(possibleCryptedLink), unknownLinkHandler);
                                                switch (ret) {
                                                case STOP:
                                                    return;
                                                case CONTINUE:
                                                    break;
                                                case BLACKLISTED:
                                                    continue mainloop;
                                                case NEXT:
                                                    loopPreventionEmbedded.put(possibleCryptedLink, this);
                                                    handleUnhandledCryptedLink(possibleCryptedLink);
                                                    continue mainloop;
                                                default:
                                                    LogController.CL().log(new IllegalStateException(ret.name()));
                                                    break;
                                                }
                                            }
                                        }
                                        if (DirectHTTPPermission.ALWAYS.equals(directHTTPPermission)) {
                                            final DISTRIBUTE ret = distributePluginForHost(httpPlugin, generation, url, createCopyOf(possibleCryptedLink));
                                            switch (ret) {
                                            case STOP:
                                                return;
                                            case CONTINUE:
                                                break;
                                            case NEXT:
                                            case BLACKLISTED:
                                                continue mainloop;
                                            default:
                                                LogController.CL().log(new IllegalStateException(ret.name()));
                                                break;
                                            }
                                        } else {
                                            // DirectHTTPPermission.FORBIDDEN
                                            continue mainloop;
                                        }
                                    }
                                } catch (final Throwable e) {
                                    LogController.CL().log(e);
                                }
                            }
                        }
                        if (unnknownHandler != null) {
                            /*
                             * CrawledLink is unhandled till now , but has an UnknownHandler set, lets call it, maybe it makes the Link
                             * handable by a Plugin
                             */
                            try {
                                unnknownHandler.unhandledCrawledLink(possibleCryptedLink, this);
                            } catch (final Throwable e) {
                                LogController.CL().log(e);
                            }
                            /* lets retry this crawledLink */
                            continue mainloopretry;
                        }
                        if (!isFtp && !isHttpJD && !isDirect) {
                            // only process non directhttp/https?viajd/ftp
                            final Boolean deeperOrFollow = distributeDeeperOrMatchingRule(generation, url, possibleCryptedLink);
                            if (Boolean.FALSE.equals(deeperOrFollow)) {
                                return;
                            } else {
                                synchronized (loopPreventionEmbedded) {
                                    if (!loopPreventionEmbedded.containsKey(possibleCryptedLink)) {
                                        final DISTRIBUTE ret = distributeEmbeddedLink(generation, url, possibleCryptedLink, null);
                                        switch (ret) {
                                        case STOP:
                                            return;
                                        case CONTINUE:
                                            break;
                                        case BLACKLISTED:
                                            continue mainloop;
                                        case NEXT:
                                            loopPreventionEmbedded.put(possibleCryptedLink, this);
                                            handleUnhandledCryptedLink(possibleCryptedLink);
                                            continue mainloop;
                                        default:
                                            LogController.CL().log(new IllegalStateException(ret.name()));
                                            break;
                                        }
                                    }
                                }
                                if (Boolean.TRUE.equals(deeperOrFollow)) {
                                    continue mainloop;
                                }
                            }
                        }
                        break mainloopretry;
                    }
                    handleUnhandledCryptedLink(possibleCryptedLink);
                }
            } finally {
                checkFinishNotify(task);
            }
        }
    }

    protected DISTRIBUTE distributeEmbeddedLink(final LinkCrawlerGeneration generation, final String url, final CrawledLink source, UnknownCrawledLinkHandler unknownCrawledLinkHandler) {
        final LinkedHashSet<String> possibleEmbeddedLinks = new LinkedHashSet<String>();
        try {
            final String sourceURL = source.getURL();
            final String queryString = new Regex(sourceURL, "\\?(.+)$").getMatch(0);
            if (StringUtils.isNotEmpty(queryString)) {
                final String[] parameters = queryString.split("\\&(?!#)", -1);
                for (final String parameter : parameters) {
                    try {
                        final String params[] = parameter.split("=", 2);
                        final String checkParam;
                        if (params.length == 1) {
                            checkParam = URLDecoder.decode(params[0], "UTF-8");
                        } else {
                            checkParam = URLDecoder.decode(params[1], "UTF-8");
                        }
                        if (checkParam.startsWith("aHR0c") || checkParam.startsWith("ZnRwOi")) {
                            String base64 = checkParam;
                            /* base64 http and ftp */
                            while (true) {
                                if (base64.length() % 4 != 0) {
                                    base64 += "=";
                                } else {
                                    break;
                                }
                            }
                            final byte[] decoded = Base64.decode(base64);
                            if (decoded != null) {
                                String possibleURLs = new String(decoded, "UTF-8");
                                if (HTMLParser.getProtocol(possibleURLs) == null) {
                                    possibleURLs = URLDecoder.decode(possibleURLs, "UTF-8");
                                }
                                if (HTMLParser.getProtocol(possibleURLs) != null) {
                                    possibleEmbeddedLinks.add(possibleURLs);
                                }
                            }
                        } else {
                            try {
                                final String maybeURL;
                                if (checkParam.contains("%3")) {
                                    maybeURL = URLDecoder.decode(checkParam, "UTF-8").replaceFirst("^:?/?/?", "");
                                } else {
                                    maybeURL = checkParam.replaceFirst("^:?/?/?", "");
                                }
                                final URL dummyURL;
                                if (HTMLParser.getProtocol(maybeURL) == null) {
                                    dummyURL = new URL("http://" + maybeURL.replaceFirst("^(.+?://)", ""));
                                } else {
                                    dummyURL = new URL(maybeURL);
                                }
                                if (dummyURL != null && dummyURL.getHost() != null && dummyURL.getHost().contains(".") && (StringUtils.isNotEmpty(dummyURL.getFile()) || StringUtils.isNotEmpty(dummyURL.getRef()))) {
                                    possibleEmbeddedLinks.add(dummyURL.toString());
                                }
                            } catch (final MalformedURLException e) {
                            }
                        }
                    } catch (final Throwable e) {
                        LogController.CL().log(e);
                    }
                }
            }
            if (StringUtils.contains(sourceURL, "aHR0c") || StringUtils.contains(sourceURL, "ZnRwOi")) {
                String base64 = new Regex(sourceURL, "(aHR0c[0-9a-zA-Z\\+\\/]+(%3D|=){0,2})").getMatch(0);// http
                if (base64 == null) {
                    base64 = new Regex(sourceURL, "(ZnRwOi[0-9a-zA-Z\\+\\/]+(%3D|=){0,2})").getMatch(0);// ftp
                }
                if (base64 != null) {
                    if (base64.contains("%3D")) {
                        base64 = URLDecoder.decode(base64, "UTF-8");
                    }
                    while (true) {
                        if (base64.length() % 4 != 0) {
                            base64 += "=";
                        } else {
                            break;
                        }
                    }
                    final byte[] decoded = Base64.decode(base64);
                    if (decoded != null) {
                        String possibleURLs = new String(decoded, "UTF-8");
                        if (HTMLParser.getProtocol(possibleURLs) == null) {
                            possibleURLs = URLDecoder.decode(possibleURLs, "UTF-8");
                        }
                        if (HTMLParser.getProtocol(possibleURLs) != null) {
                            possibleEmbeddedLinks.add(possibleURLs);
                        }
                    }
                }
            }
        } catch (final Throwable e) {
            LogController.CL().log(e);
        }
        if (possibleEmbeddedLinks.size() > 0) {
            final ArrayList<CrawledLink> embeddedLinks = new ArrayList<CrawledLink>();
            for (final String possibleURL : possibleEmbeddedLinks) {
                final List<CrawledLink> links = find(generation, source, possibleURL, null, source.isCrawlDeep(), false);
                if (links != null) {
                    embeddedLinks.addAll(links);
                }
            }
            if (embeddedLinks.size() > 0) {
                final boolean singleDest = embeddedLinks.size() == 1;
                final String[] sourceURLs = getAndClearSourceURLs(source);
                final CrawledLinkModifier sourceLinkModifier = source.getCustomCrawledLinkModifier();
                source.setCustomCrawledLinkModifier(null);
                source.setBrokenCrawlerHandler(null);
                for (final CrawledLink embeddedLink : embeddedLinks) {
                    embeddedLink.setUnknownHandler(unknownCrawledLinkHandler);
                    forwardCrawledLinkInfos(source, embeddedLink, sourceLinkModifier, sourceURLs, singleDest);
                }
                crawl(generation, embeddedLinks);
                return DISTRIBUTE.NEXT;
            }
        }
        return DISTRIBUTE.CONTINUE;
    }

    public List<LinkCrawlerRule> getLinkCrawlerRules() {
        List<LinkCrawlerRule> ret = linkCrawlerRules.get();
        if (ret == null) {
            synchronized (LINKCRAWLERRULESLOCK) {
                ret = linkCrawlerRules.get();
                if (ret == null) {
                    linkCrawlerRules.set(listLinkCrawlerRules());
                    return getLinkCrawlerRules();
                }
            }
        }
        if (ret.size() == 0) {
            return null;
        } else {
            return ret;
        }
    }

    protected LinkCrawlerRule getFirstMatchingRule(CrawledLink link, String url, LinkCrawlerRule.RULE... ruleTypes) {
        final List<LinkCrawlerRule> rules = getLinkCrawlerRules();
        if (rules != null && (StringUtils.startsWithCaseInsensitive(url, "file:/") || StringUtils.startsWithCaseInsensitive(url, "http://") || StringUtils.startsWithCaseInsensitive(url, "https://"))) {
            for (final LinkCrawlerRule.RULE ruleType : ruleTypes) {
                for (final LinkCrawlerRule rule : rules) {
                    if (ruleType.equals(rule.getRule()) && rule.matches(url)) {
                        if (rule.getMaxDecryptDepth() == -1) {
                            return rule;
                        } else {
                            final Iterator<CrawledLink> it = link.iterator();
                            int depth = 0;
                            while (it.hasNext()) {
                                final CrawledLink next = it.next();
                                final LinkCrawlerRule matchingRule = next.getMatchingRule();
                                if (matchingRule != null && matchingRule.getId() == rule.getId()) {
                                    depth++;
                                }
                            }
                            if (depth <= rule.getMaxDecryptDepth()) {
                                // okay
                                return rule;
                            } else {
                                // too deep
                                continue;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    public List<LazyCrawlerPlugin> getSortedLazyCrawlerPlugins() {
        final LinkCrawler parent = getParent();
        if (parent != null) {
            return parent.getSortedLazyCrawlerPlugins();
        } else {
            if (unsortedLazyCrawlerPlugins == null) {
                unsortedLazyCrawlerPlugins = CrawlerPluginController.getInstance().list();
            }
            List<LazyCrawlerPlugin> ret = sortedLazyCrawlerPlugins.get();
            if (ret == null) {
                synchronized (sortedLazyCrawlerPlugins) {
                    ret = sortedLazyCrawlerPlugins.get();
                    if (ret == null) {
                        /* sort cHosts according to their usage */
                        ret = new ArrayList<LazyCrawlerPlugin>(unsortedLazyCrawlerPlugins.size());
                        final List<LazyCrawlerPlugin> allPlugins = new ArrayList<LazyCrawlerPlugin>(unsortedLazyCrawlerPlugins);
                        try {
                            final Map<String, Object> pluginMap = new HashMap<String, Object>();
                            for (final LazyCrawlerPlugin plugin : allPlugins) {
                                final Object entry = pluginMap.get(plugin.getDisplayName());
                                if (entry == null) {
                                    pluginMap.put(plugin.getDisplayName(), plugin);
                                } else if (entry instanceof List) {
                                    ((List<LazyCrawlerPlugin>) entry).add(plugin);
                                } else {
                                    final ArrayList<LazyCrawlerPlugin> list = new ArrayList<LazyCrawlerPlugin>();
                                    list.add((LazyCrawlerPlugin) entry);
                                    list.add(plugin);
                                    pluginMap.put(plugin.getDisplayName(), list);
                                }
                            }
                            Collections.sort(allPlugins, new Comparator<LazyCrawlerPlugin>() {
                                public final int compare(final long x, final long y) {
                                    return (x < y) ? 1 : ((x == y) ? 0 : -1);
                                }

                                public final int compare(final boolean x, final boolean y) {
                                    return (x == y) ? 0 : (x ? 1 : -1);
                                }

                                @Override
                                public int compare(LazyCrawlerPlugin o1, LazyCrawlerPlugin o2) {
                                    final int ret = compare(o1.getPluginUsage(), o2.getPluginUsage());
                                    if (ret == 0) {
                                        return compare(o1.hasFeature(FEATURE.GENERIC), o2.hasFeature(FEATURE.GENERIC));
                                    } else {
                                        return ret;
                                    }
                                }
                            });
                            for (final LazyCrawlerPlugin plugin : allPlugins) {
                                final Object entry = pluginMap.remove(plugin.getDisplayName());
                                if (entry == null) {
                                    if (pluginMap.isEmpty()) {
                                        break;
                                    } else {
                                        continue;
                                    }
                                } else if (entry instanceof LazyCrawlerPlugin) {
                                    ret.add((LazyCrawlerPlugin) entry);
                                } else {
                                    final List<LazyCrawlerPlugin> list = (List<LazyCrawlerPlugin>) entry;
                                    sortLazyCrawlerPluginByInterfaceVersion(list);
                                    ret.addAll(list);
                                }
                            }
                        } catch (final Throwable e) {
                            LogController.CL(true).log(e);
                        }
                        if (ret == null || ret.size() == 0) {
                            ret = allPlugins;
                        }
                        sortedLazyCrawlerPlugins.compareAndSet(null, ret);
                    }
                }
            }
            return ret;
        }
    }

    protected void sortLazyCrawlerPluginByInterfaceVersion(final List<LazyCrawlerPlugin> plugins) {
        Collections.sort(plugins, new Comparator<LazyCrawlerPlugin>() {
            @Override
            public final int compare(final LazyCrawlerPlugin lazyCrawlerPlugin1, final LazyCrawlerPlugin lazyCrawlerPlugin2) {
                final int i1 = lazyCrawlerPlugin1.getLazyPluginClass().getInterfaceVersion();
                final int i2 = lazyCrawlerPlugin2.getLazyPluginClass().getInterfaceVersion();
                if (i1 == i2) {
                    return 0;
                } else if (i1 > i2) {
                    return -1;
                } else {
                    return 1;
                }
            }
        });
    }

    public List<LazyHostPlugin> getSortedLazyHostPlugins() {
        final LinkCrawler parent = getParent();
        if (parent != null) {
            return parent.getSortedLazyHostPlugins();
        } else {
            /* sort pHosts according to their usage */
            List<LazyHostPlugin> ret = sortedLazyHostPlugins.get();
            if (ret == null) {
                synchronized (sortedLazyHostPlugins) {
                    ret = sortedLazyHostPlugins.get();
                    if (ret == null) {
                        ret = new ArrayList<LazyHostPlugin>();
                        for (final LazyHostPlugin lazyHostPlugin : HostPluginController.getInstance().list()) {
                            if (!HTTP_LINKS.equals(lazyHostPlugin.getDisplayName()) && !"ftp".equals(lazyHostPlugin.getDisplayName()) && !DIRECT_HTTP.equals(lazyHostPlugin.getDisplayName())) {
                                ret.add(lazyHostPlugin);
                            }
                        }
                        try {
                            Collections.sort(ret, new Comparator<LazyHostPlugin>() {
                                public final int compare(long x, long y) {
                                    return (x < y) ? 1 : ((x == y) ? 0 : -1);
                                }

                                @Override
                                public final int compare(LazyHostPlugin o1, LazyHostPlugin o2) {
                                    return compare(o1.getPluginUsage(), o2.getPluginUsage());
                                }
                            });
                        } catch (final Throwable e) {
                            LogController.CL(true).log(e);
                        }
                        sortedLazyHostPlugins.compareAndSet(null, ret);
                    }
                }
            }
            return ret;
        }
    }

    protected boolean resetSortedLazyCrawlerPlugins(List<LazyCrawlerPlugin> resetSortedLazyCrawlerPlugins) {
        final LinkCrawler parent = getParent();
        if (parent != null) {
            return parent.resetSortedLazyCrawlerPlugins(resetSortedLazyCrawlerPlugins);
        } else {
            return sortedLazyCrawlerPlugins.compareAndSet(resetSortedLazyCrawlerPlugins, null);
        }
    }

    protected boolean resetSortedLazyHostPlugins(List<LazyHostPlugin> lazyHostPlugins) {
        final LinkCrawler parent = getParent();
        if (parent != null) {
            return parent.resetSortedLazyHostPlugins(lazyHostPlugins);
        } else {
            return sortedLazyHostPlugins.compareAndSet(lazyHostPlugins, null);
        }
    }

    protected DirectHTTPPermission getDirectHTTPPermission() {
        return directHTTPPermission;
    }

    public List<CrawledLink> getCryptedLinks(LazyCrawlerPlugin lazyC, CrawledLink source, CrawledLinkModifier modifier) {
        final String[] matches = getMatchingLinks(lazyC.getPattern(), source, modifier);
        if (matches != null && matches.length > 0) {
            final ArrayList<CrawledLink> ret = new ArrayList<CrawledLink>();
            for (final String match : matches) {
                final CryptedLink cryptedLink;
                if (matches.length == 1 && match.equals(source.getURL())) {
                    cryptedLink = new CryptedLink(source);
                } else {
                    cryptedLink = new CryptedLink(match, source);
                }
                cryptedLink.setLazyC(lazyC);
                final CrawledLink link = crawledLinkFactorybyCryptedLink(cryptedLink);
                forwardCrawledLinkInfos(source, link, modifier, null, null);
                if ((source.getUrlLink() == null || StringUtils.equals(source.getUrlLink(), link.getURL())) && (source.getDownloadLink() == null || source.getDownloadLink().getProperties().isEmpty())) {
                    // modify sourceLink because link arise from source(getMatchingLinks)
                    //
                    // keep DownloadLinks with non empty properties
                    link.setCrawlDeep(source.isCrawlDeep());
                    link.setSourceLink(source.getSourceLink());
                    if (!(cryptedLink.getSource() instanceof String)) {
                        cryptedLink.setCryptedUrl(match);
                    }
                    cryptedLink.setSourceLink(source.getSourceLink());
                    if (source.getMatchingRule() != null) {
                        link.setMatchingRule(source.getMatchingRule());
                    }
                }
                ret.add(link);
            }
            return ret;
        }
        return null;
    }

    protected String[] getMatchingLinks(Pattern pattern, CrawledLink source, CrawledLinkModifier modifier) {
        final String[] ret = new Regex(source.getURL(), pattern).getColumn(-1);
        if (ret != null && ret.length > 0) {
            for (int index = 0; index < ret.length; index++) {
                String match = ret[index];
                match = match.trim();
                while (match.length() > 2 && match.charAt(0) == '<' && match.charAt(match.length() - 1) == '>') {
                    match = match.substring(1, match.length() - 1);
                }
                while (match.length() > 2 && match.charAt(0) == '\"' && match.charAt(match.length() - 1) == '\"') {
                    match = match.substring(1, match.length() - 1);
                }
                ret[index] = match.trim();
                if (StringUtils.equals(source.getURL(), ret[index])) {
                    ret[index] = source.getURL();
                }
            }
            return ret;
        }
        return null;
    }

    public List<CrawledLink> getCrawledLinks(Pattern pattern, CrawledLink source, CrawledLinkModifier modifier) {
        final String[] matches = getMatchingLinks(pattern, source, modifier);
        if (matches != null && matches.length > 0) {
            final ArrayList<CrawledLink> ret = new ArrayList<CrawledLink>();
            for (final String match : matches) {
                final CrawledLink link = crawledLinkFactorybyURL(match);
                forwardCrawledLinkInfos(source, link, modifier, null, null);
                if ((source.getUrlLink() == null || StringUtils.equals(source.getUrlLink(), link.getURL())) && (source.getDownloadLink() == null || source.getDownloadLink().getProperties().isEmpty())) {
                    // modify sourceLink because link arise from source(getMatchingLinks)
                    //
                    // keep DownloadLinks with non empty properties
                    link.setSourceLink(source.getSourceLink());
                    if (source.getMatchingRule() != null) {
                        link.setMatchingRule(source.getMatchingRule());
                    }
                }
                ret.add(link);
            }
            return ret;
        }
        return null;
    }

    protected void processHostPlugin(final LinkCrawlerGeneration generation, LazyHostPlugin pHost, CrawledLink possibleCryptedLink) {
        final CrawledLinkModifier parentLinkModifier = possibleCryptedLink.getCustomCrawledLinkModifier();
        possibleCryptedLink.setCustomCrawledLinkModifier(null);
        possibleCryptedLink.setBrokenCrawlerHandler(null);
        if (pHost == null || possibleCryptedLink.getURL() == null || this.isCrawledLinkFiltered(possibleCryptedLink)) {
            return;
        }
        final LinkCrawlerTask task;
        if ((task = checkStartNotify(generation, "processHostPlugin:" + pHost + "|" + possibleCryptedLink.getURL())) != null) {
            try {
                final String[] sourceURLs = getAndClearSourceURLs(possibleCryptedLink);
                /*
                 * use a new PluginClassLoader here
                 */
                final PluginForHost wplg = pHost.newInstance(getPluginClassLoaderChild());
                if (wplg != null) {
                    /* now we run the plugin and let it find some links */
                    final LinkCrawlerThread lct = getCurrentLinkCrawlerThread();
                    Object owner = null;
                    LinkCrawler previousCrawler = null;
                    boolean oldDebug = false;
                    boolean oldVerbose = false;
                    LogInterface oldLogger = null;
                    try {
                        final LogInterface logger = LogController.getFastPluginLogger(wplg.getCrawlerLoggerID(possibleCryptedLink));
                        logger.info("Processing: " + possibleCryptedLink.getURL());
                        if (lct != null) {
                            /* mark thread to be used by crawler plugin */
                            owner = lct.getCurrentOwner();
                            lct.setCurrentOwner(wplg);
                            previousCrawler = lct.getCurrentLinkCrawler();
                            lct.setCurrentLinkCrawler(this);
                            /* save old logger/states */
                            oldLogger = lct.getLogger();
                            oldDebug = lct.isDebug();
                            oldVerbose = lct.isVerbose();
                            /* set new logger and set verbose/debug true */
                            lct.setLogger(logger);
                            lct.setVerbose(true);
                            lct.setDebug(true);
                        }
                        final Browser br = wplg.createNewBrowserInstance();
                        wplg.setBrowser(br);
                        wplg.setLogger(logger);
                        wplg.init();
                        String url = possibleCryptedLink.getURL();
                        FilePackage sourcePackage = null;
                        if (possibleCryptedLink.getDownloadLink() != null) {
                            sourcePackage = possibleCryptedLink.getDownloadLink().getFilePackage();
                            if (FilePackage.isDefaultFilePackage(sourcePackage)) {
                                /* we don't want the various filePackage getting used */
                                sourcePackage = null;
                            }
                        }
                        final long startTime = Time.systemIndependentCurrentJVMTimeMillis();
                        final List<CrawledLink> crawledLinks = new ArrayList<CrawledLink>();
                        try {
                            wplg.setCurrentLink(possibleCryptedLink);
                            final List<DownloadLink> hosterLinks = wplg.getDownloadLinks(possibleCryptedLink, url, sourcePackage);
                            if (hosterLinks != null) {
                                final UrlProtection protection = wplg.getUrlProtection(hosterLinks);
                                if (protection != null && protection != UrlProtection.UNSET) {
                                    for (DownloadLink dl : hosterLinks) {
                                        if (dl.getUrlProtection() == UrlProtection.UNSET) {
                                            dl.setUrlProtection(protection);
                                        }
                                    }
                                }
                                for (final DownloadLink hosterLink : hosterLinks) {
                                    try {
                                        wplg.correctDownloadLink(hosterLink);
                                    } catch (final Throwable e) {
                                        LogController.CL().log(e);
                                    }
                                    crawledLinks.add(wplg.convert(hosterLink));
                                }
                            }
                            /* in case the function returned without exceptions, we can clear log */
                            if (logger instanceof ClearableLogInterface) {
                                ((ClearableLogInterface) logger).clear();
                            }
                        } finally {
                            wplg.setCurrentLink(null);
                            final long endTime = Time.systemIndependentCurrentJVMTimeMillis() - startTime;
                            wplg.getLazyP().updateParseRuntime(endTime);
                            /* close the logger */
                            if (logger instanceof ClosableLogInterface) {
                                ((ClosableLogInterface) logger).close();
                            }
                        }
                        if (crawledLinks.size() > 0) {
                            final boolean singleDest = crawledLinks.size() == 1;
                            for (final CrawledLink crawledLink : crawledLinks) {
                                forwardCrawledLinkInfos(possibleCryptedLink, crawledLink, parentLinkModifier, sourceURLs, singleDest);
                                if (possibleCryptedLink.getUrlLink() == null || StringUtils.equals(possibleCryptedLink.getUrlLink(), crawledLink.getURL())) {
                                    // modify sourceLink because crawledLink arise from possibleCryptedLink(wplg.getDownloadLinks)
                                    crawledLink.setSourceLink(possibleCryptedLink.getSourceLink());
                                    if (possibleCryptedLink.getMatchingRule() != null) {
                                        crawledLink.setMatchingRule(possibleCryptedLink.getMatchingRule());
                                    }
                                }
                                distributeFinalCrawledLink(generation, crawledLink);
                            }
                        }
                    } finally {
                        if (lct != null) {
                            /* reset thread to last known used state */
                            lct.setCurrentOwner(owner);
                            lct.setCurrentLinkCrawler(previousCrawler);
                            lct.setLogger(oldLogger);
                            lct.setVerbose(oldVerbose);
                            lct.setDebug(oldDebug);
                        }
                    }
                } else {
                    LogController.CL().info("Hoster Plugin not available:" + pHost.getDisplayName());
                }
            } catch (Throwable e) {
                LogController.CL().log(e);
            } finally {
                /* restore old ClassLoader for current Thread */
                checkFinishNotify(task);
            }
        }
    }

    public String[] getAndClearSourceURLs(final CrawledLink link) {
        final ArrayList<String> sources = new ArrayList<String>();
        CrawledLink next = link;
        CrawledLink previous = link;
        while (next != null) {
            final CrawledLink current = next;
            next = current.getSourceLink();
            final String currentURL = cleanURL(current.getURL());
            if (currentURL != null) {
                if (sources.size() == 0) {
                    sources.add(currentURL);
                } else {
                    if (current.getMatchingRule() == null || current.getMatchingRule() != previous.getMatchingRule()) {
                        final String previousURL = sources.get(sources.size() - 1);
                        if (!StringUtils.equals(currentURL, previousURL)) {
                            sources.add(currentURL);
                        }
                    }
                }
            }
            previous = current;
        }
        link.setSourceUrls(null);
        final String customSourceUrl = getReferrerUrl(link);
        if (customSourceUrl != null) {
            sources.add(customSourceUrl);
        }
        if (sources.size() == 0) {
            return null;
        } else {
            return sources.toArray(new String[] {});
        }
    }

    public String getReferrerUrl(final CrawledLink link) {
        if (link != null) {
            final LinkCollectingJob job = link.getSourceJob();
            if (job != null) {
                final String customSourceUrl = job.getCustomSourceUrl();
                if (customSourceUrl != null) {
                    return customSourceUrl;
                }
            }
        }
        if (this instanceof JobLinkCrawler) {
            final LinkCollectingJob job = ((JobLinkCrawler) this).getJob();
            if (job != null) {
                return job.getCustomSourceUrl();
            }
        }
        return null;
    }

    public static String getUnsafeName(String unsafeName, String currentName) {
        if (unsafeName != null) {
            String extension = Files.getExtension(unsafeName);
            if (extension == null && unsafeName.indexOf('.') < 0) {
                String unsafeSourceModified = null;
                if (unsafeName.indexOf('_') > 0) {
                    unsafeSourceModified = unsafeName.replaceAll("_", ".");
                    extension = Files.getExtension(unsafeSourceModified);
                }
                if (extension == null && unsafeName.indexOf('-') > 0) {
                    unsafeSourceModified = unsafeName.replaceAll("-", ".");
                    extension = Files.getExtension(unsafeSourceModified);
                }
                if (extension != null) {
                    unsafeName = unsafeSourceModified;
                }
            }
            if (extension != null && !StringUtils.equals(currentName, unsafeName)) {
                return unsafeName;
            }
        }
        return null;
    }

    /**
     * in case link contains rawURL/CryptedLink we return downloadLink from sourceLink
     *
     * @param link
     * @return
     */
    private DownloadLink getLatestDownloadLink(CrawledLink link) {
        final DownloadLink ret = link.getDownloadLink();
        if (ret == null && link.getSourceLink() != null) {
            return link.getSourceLink().getDownloadLink();
        } else {
            return ret;
        }
    }

    private CryptedLink getLatestCryptedLink(CrawledLink link) {
        final CryptedLink ret = link.getCryptedLink();
        if (ret == null && link.getSourceLink() != null) {
            return link.getSourceLink().getCryptedLink();
        } else {
            return ret;
        }
    }

    private void forwardCryptedLinkInfos(final CrawledLink sourceCrawledLink, final CryptedLink destCryptedLink) {
        if (sourceCrawledLink != null && destCryptedLink != null) {
            String pw = null;
            final DownloadLink latestDownloadLink = getLatestDownloadLink(sourceCrawledLink);
            if (latestDownloadLink != null) {
                pw = latestDownloadLink.getDownloadPassword();
            }
            if (StringUtils.isEmpty(pw)) {
                final CryptedLink latestCryptedLink = getLatestCryptedLink(sourceCrawledLink);
                if (latestCryptedLink != null) {
                    pw = latestCryptedLink.getDecrypterPassword();
                }
            }
            if (StringUtils.isEmpty(pw) && LinkCrawler.this instanceof JobLinkCrawler && ((JobLinkCrawler) LinkCrawler.this).getJob() != null) {
                pw = ((JobLinkCrawler) LinkCrawler.this).getJob().getCrawlerPassword();
            }
            destCryptedLink.setDecrypterPassword(pw);
        }
    }

    protected void forwardCrawledLinkInfos(final CrawledLink sourceCrawledLink, final CrawledLink destCrawledLink, final CrawledLinkModifier sourceLinkModifier, final String sourceURLs[], final Boolean singleDestCrawledLink) {
        if (sourceCrawledLink != null && destCrawledLink != null && sourceCrawledLink != destCrawledLink) {
            destCrawledLink.setSourceLink(sourceCrawledLink);
            destCrawledLink.setOrigin(sourceCrawledLink.getOrigin());
            destCrawledLink.setSourceUrls(sourceURLs);
            destCrawledLink.setMatchingFilter(sourceCrawledLink.getMatchingFilter());
            forwardCryptedLinkInfos(sourceCrawledLink, destCrawledLink.getCryptedLink());
            forwardDownloadLinkInfos(getLatestDownloadLink(sourceCrawledLink), destCrawledLink.getDownloadLink(), singleDestCrawledLink);
            if (Boolean.TRUE.equals(singleDestCrawledLink) && sourceCrawledLink.isNameSet()) {
                // forward customized name, eg from container plugins
                destCrawledLink.setName(sourceCrawledLink.getName());
            }
            final CrawledLinkModifier destCustomModifier = destCrawledLink.getCustomCrawledLinkModifier();
            if (destCustomModifier == null) {
                destCrawledLink.setCustomCrawledLinkModifier(sourceLinkModifier);
            } else if (sourceLinkModifier != null) {
                final List<CrawledLinkModifier> modifiers = new ArrayList<CrawledLinkModifier>();
                modifiers.add(sourceLinkModifier);
                modifiers.add(destCustomModifier);
                destCrawledLink.setCustomCrawledLinkModifier(new CrawledLinkModifiers(modifiers));
            }
            // if we decrypted a dlc,source.getDesiredPackageInfo() is null, and dest might already have package infos from the container.
            // maybe it would be even better to merge the packageinfos
            // However. if there are crypted links in the container, it may be up to the decrypterplugin to decide
            // example: share-links.biz uses CNL to post links to localhost. the dlc origin get's lost on such a way
            final PackageInfo dpi = sourceCrawledLink.getDesiredPackageInfo();
            if (dpi != null) {
                destCrawledLink.setDesiredPackageInfo(dpi.getCopy());
            }
            final ArchiveInfo destArchiveInfo;
            if (destCrawledLink.hasArchiveInfo()) {
                destArchiveInfo = destCrawledLink.getArchiveInfo();
            } else {
                destArchiveInfo = null;
            }
            if (sourceCrawledLink.hasArchiveInfo()) {
                if (destArchiveInfo == null) {
                    destCrawledLink.setArchiveInfo(new ArchiveInfo().migrate(sourceCrawledLink.getArchiveInfo()));
                } else {
                    destArchiveInfo.migrate(sourceCrawledLink.getArchiveInfo());
                }
            }
            convertFilePackageInfos(destCrawledLink);
            permanentOffline(destCrawledLink);
        }
    }

    private PackageInfo convertFilePackageInfos(CrawledLink link) {
        if (link.getDownloadLink() == null) {
            return null;
        }
        final FilePackage fp = link.getDownloadLink().getFilePackage();
        if (!FilePackage.isDefaultFilePackage(fp)) {
            fp.remove(link.getDownloadLink());
            if (link.getDesiredPackageInfo() != null && Boolean.TRUE.equals(link.getDesiredPackageInfo().isAllowInheritance())) {
                final Boolean allowInheritance = fp.isAllowInheritance();
                if (allowInheritance == null || allowInheritance == Boolean.FALSE) {
                    return link.getDesiredPackageInfo();
                }
            }
            PackageInfo fpi = null;
            if (StringUtils.isNotEmpty(fp.getDownloadDirectory()) && !fp.getDownloadDirectory().equals(defaultDownloadFolder)) {
                // do not set downloadfolder if it is the defaultfolder
                if (fpi == null && (fpi = link.getDesiredPackageInfo()) == null) {
                    fpi = new PackageInfo();
                }
                fpi.setDestinationFolder(CrossSystem.fixPathSeparators(fp.getDownloadDirectory() + File.separator));
            }
            final String name = LinknameCleaner.cleanPackagename(fp.getName(), false, false, LinknameCleaner.EXTENSION_SETTINGS.REMOVE_KNOWN, fp.isCleanupPackageName());
            if (StringUtils.isNotEmpty(name)) {
                if (fpi == null && (fpi = link.getDesiredPackageInfo()) == null) {
                    fpi = new PackageInfo();
                }
                fpi.setName(name);
            }
            final Boolean allowMerge = fp.isAllowMerge();
            if (allowMerge != null) {
                if (allowMerge == Boolean.TRUE) {
                    if (fpi != null || (fpi = link.getDesiredPackageInfo()) != null) {
                        fpi.setUniqueId(null);
                    }
                } else {
                    if (fpi == null && (fpi = link.getDesiredPackageInfo()) == null) {
                        fpi = new PackageInfo();
                    }
                    fpi.setUniqueId(fp.getUniqueID());
                }
            }
            final String packageKey = fp.getPackageKey();
            if (packageKey != null) {
                if (fpi == null && (fpi = link.getDesiredPackageInfo()) == null) {
                    fpi = new PackageInfo();
                }
                fpi.setPackageKey(packageKey);
            }
            final Boolean ignoreVarious = fp.isIgnoreVarious();
            if (ignoreVarious != null) {
                if (fpi == null && (fpi = link.getDesiredPackageInfo()) == null) {
                    fpi = new PackageInfo();
                }
                fpi.setIgnoreVarious(ignoreVarious);
            }
            final Boolean allowInheritance = fp.isAllowInheritance();
            if (allowInheritance != null) {
                if (fpi == null && (fpi = link.getDesiredPackageInfo()) == null) {
                    fpi = new PackageInfo();
                }
                fpi.setAllowInheritance(allowInheritance);
            }
            if (StringUtils.isNotEmpty(fp.getComment())) {
                if (fpi == null && (fpi = link.getDesiredPackageInfo()) == null) {
                    fpi = new PackageInfo();
                }
                fpi.setComment(fp.getComment());
            }
            if (fpi != null) {
                link.setDesiredPackageInfo(fpi);
            }
            return fpi;
        }
        return null;
    }

    private void permanentOffline(CrawledLink link) {
        final DownloadLink dl = link.getDownloadLink();
        try {
            if (dl != null && dl.getDefaultPlugin().getLazyP().getClassName().endsWith("r.Offline")) {
                final PackageInfo dpi;
                if (link.getDesiredPackageInfo() == null) {
                    dpi = new PackageInfo();
                } else {
                    dpi = link.getDesiredPackageInfo();
                }
                dpi.setUniqueId(PERMANENT_OFFLINE_ID);
                link.setDesiredPackageInfo(dpi);
            }
        } catch (final Throwable e) {
        }
    }

    protected void forwardDownloadLinkInfos(final DownloadLink sourceDownloadLink, final DownloadLink destDownloadLink, final Boolean singleDestDownloadLink) {
        if (sourceDownloadLink != null && destDownloadLink != null && sourceDownloadLink != destDownloadLink) {
            /* create a copy of ArrayList */
            final List<String> srcPWs = sourceDownloadLink.getSourcePluginPasswordList();
            if (srcPWs != null && srcPWs.size() > 0) {
                destDownloadLink.setSourcePluginPasswordList(new ArrayList<String>(srcPWs));
            }
            if (sourceDownloadLink.getComment() != null && destDownloadLink.getComment() == null) {
                destDownloadLink.setComment(sourceDownloadLink.getComment());
            }
            if (sourceDownloadLink.getContainerUrl() != null && destDownloadLink.getContainerUrl() == null) {
                destDownloadLink.setContainerUrl(sourceDownloadLink.getContainerUrl());
            }
            if (destDownloadLink.getUrlProtection() == UrlProtection.UNSET && sourceDownloadLink.getUrlProtection() != UrlProtection.UNSET) {
                destDownloadLink.setUrlProtection(sourceDownloadLink.getUrlProtection());
            }
            if (Boolean.TRUE.equals(singleDestDownloadLink)) {
                if (!destDownloadLink.isNameSet()) {
                    if (sourceDownloadLink.isNameSet()) {
                        destDownloadLink.setName(sourceDownloadLink.getName());
                    } else {
                        final String name = getUnsafeName(sourceDownloadLink.getName(), destDownloadLink.getName());
                        if (name != null) {
                            destDownloadLink.setName(name);
                        }
                    }
                }
                if (sourceDownloadLink.getForcedFileName() != null && destDownloadLink.getForcedFileName() == null) {
                    destDownloadLink.setForcedFileName(sourceDownloadLink.getForcedFileName());
                }
                if (sourceDownloadLink.getFinalFileName() != null && destDownloadLink.getFinalFileName() == null) {
                    destDownloadLink.setFinalFileName(sourceDownloadLink.getFinalFileName());
                }
                if (sourceDownloadLink.isAvailabilityStatusChecked() && sourceDownloadLink.getAvailableStatus() != destDownloadLink.getAvailableStatus() && !destDownloadLink.isAvailabilityStatusChecked()) {
                    destDownloadLink.setAvailableStatus(sourceDownloadLink.getAvailableStatus());
                }
                if (sourceDownloadLink.getContentUrl() != null && destDownloadLink.getContentUrl() == null) {
                    destDownloadLink.setContentUrl(sourceDownloadLink.getContentUrl());
                }
                if (sourceDownloadLink.getVerifiedFileSize() >= 0 && destDownloadLink.getVerifiedFileSize() < 0) {
                    destDownloadLink.setVerifiedFileSize(sourceDownloadLink.getVerifiedFileSize());
                }
                if (sourceDownloadLink.hasTempProperties()) {
                    destDownloadLink.getTempProperties().setProperties(sourceDownloadLink.getTempProperties().getProperties());
                }
                final Map<String, Object> sourceProperties = sourceDownloadLink.getProperties();
                if (sourceProperties != null && !sourceProperties.isEmpty()) {
                    final Map<String, Object> destProperties = destDownloadLink.getProperties();
                    if (destProperties == null || destProperties.isEmpty()) {
                        destDownloadLink.setProperties(sourceProperties);
                    } else {
                        for (Entry<String, Object> property : sourceProperties.entrySet()) {
                            if (!destDownloadLink.hasProperty(property.getKey())) {
                                destDownloadLink.setProperty(property.getKey(), property.getValue());
                            }
                        }
                    }
                }
                if (sourceDownloadLink.getView().getBytesTotal() >= 0 && destDownloadLink.getKnownDownloadSize() < 0) {
                    destDownloadLink.setDownloadSize(sourceDownloadLink.getView().getBytesTotal());
                }
            }
        }
    }

    public void stopCrawling() {
        stopCrawling(true);
    }

    public void stopCrawling(final boolean stopChildren) {
        final LinkCrawlerGeneration generation = linkCrawlerGeneration.getAndSet(null);
        if (generation != null) {
            generation.invalidate();
        }
        if (stopChildren) {
            for (final LinkCrawler child : getChildren()) {
                child.stopCrawling(true);
            }
        }
    }

    public boolean waitForCrawling() {
        return waitForCrawling(true);
    }

    private final static Object WAIT = new Object();

    public boolean waitForCrawling(final boolean waitForChildren) {
        while (isRunning(waitForChildren)) {
            synchronized (WAIT) {
                if (isRunning(waitForChildren)) {
                    try {
                        WAIT.wait(1000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }
        return isRunning(waitForChildren) == false;
    }

    public List<LinkCrawler> getChildren() {
        synchronized (children) {
            return new ArrayList<LinkCrawler>(children.keySet());
        }
    }

    public LinkCrawler getRoot() {
        final LinkCrawler parent = getParent();
        if (parent != null) {
            return parent.getRoot();
        } else {
            return this;
        }
    }

    public boolean isRunning() {
        return isRunning(true);
    }

    public boolean isRunning(final boolean checkChildren) {
        synchronized (CRAWLER) {
            if (tasks.size() > 0) {
                return true;
            }
        }
        if (checkChildren) {
            for (final LinkCrawler child : getChildren()) {
                if (child.isRunning(true)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static boolean isCrawling() {
        synchronized (CRAWLER) {
            return CRAWLER.size() > 0;
        }
    }

    protected void container(final LinkCrawlerGeneration generation, final PluginsC oplg, final CrawledLink cryptedLink) {
        final CrawledLinkModifier parentLinkModifier = cryptedLink.getCustomCrawledLinkModifier();
        cryptedLink.setCustomCrawledLinkModifier(null);
        cryptedLink.setBrokenCrawlerHandler(null);
        if (oplg == null || cryptedLink.getURL() == null) {
            return;
        } else if (isCrawledLinkDuplicated(duplicateFinderContainer, cryptedLink)) {
            onCrawledLinkDuplicate(cryptedLink, DUPLICATE.CONTAINER);
            return;
        } else if (this.isCrawledLinkFiltered(cryptedLink)) {
            return;
        }
        final LinkCrawlerTask task;
        if ((task = checkStartNotify(generation, "containerPlugin:" + oplg.getName() + "|" + cryptedLink.getURL())) != null) {
            try {
                final String[] sourceURLs = getAndClearSourceURLs(cryptedLink);
                processedLinksCounter.incrementAndGet();
                /* set new PluginClassLoaderChild because ContainerPlugin maybe uses Hoster/Crawler */
                final PluginsC plg;
                try {
                    plg = oplg.newPluginInstance();
                } catch (final Throwable e) {
                    LogController.CL().log(e);
                    return;
                }
                /* now we run the plugin and let it find some links */
                final LinkCrawlerThread lct = getCurrentLinkCrawlerThread();
                Object owner = null;
                LinkCrawler previousCrawler = null;
                boolean oldDebug = false;
                boolean oldVerbose = false;
                LogInterface oldLogger = null;
                try {
                    final LogInterface logger = LogController.getFastPluginLogger(plg.getName());
                    if (lct != null) {
                        /* mark thread to be used by crawler plugin */
                        owner = lct.getCurrentOwner();
                        lct.setCurrentOwner(plg);
                        previousCrawler = lct.getCurrentLinkCrawler();
                        lct.setCurrentLinkCrawler(this);
                        /* save old logger/states */
                        oldLogger = lct.getLogger();
                        oldDebug = lct.isDebug();
                        oldVerbose = lct.isVerbose();
                        /* set new logger and set verbose/debug true */
                        lct.setLogger(logger);
                        lct.setVerbose(true);
                        lct.setDebug(true);
                    }
                    plg.setLogger(logger);
                    try {
                        final List<CrawledLink> decryptedPossibleLinks = plg.decryptContainer(cryptedLink);
                        /* in case the function returned without exceptions, we can clear log */
                        if (logger instanceof ClearableLogInterface) {
                            ((ClearableLogInterface) logger).clear();
                        }
                        if (decryptedPossibleLinks != null && decryptedPossibleLinks.size() > 0) {
                            /* we found some links, distribute them */
                            final boolean singleDest = decryptedPossibleLinks.size() == 1;
                            for (CrawledLink decryptedPossibleLink : decryptedPossibleLinks) {
                                forwardCrawledLinkInfos(cryptedLink, decryptedPossibleLink, parentLinkModifier, sourceURLs, singleDest);
                            }
                            if (insideCrawlerPlugin()) {
                                /*
                                 * direct decrypt this link because we are already inside a LinkCrawlerThread and this avoids deadlocks on
                                 * plugin waiting for linkcrawler results
                                 */
                                if (!generation.isValid()) {
                                    /* LinkCrawler got aborted! */
                                    return;
                                }
                                distribute(generation, decryptedPossibleLinks);
                            } else {
                                final LinkCrawlerTask innerTask;
                                if ((innerTask = checkStartNotify(generation, task.getTaskID() + "|containerPool")) != null) {
                                    /* enqueue distributing of the links */
                                    threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation, innerTask) {
                                        @Override
                                        public long getAverageRuntime() {
                                            final Long ret = getDefaultAverageRuntime();
                                            if (ret != null) {
                                                return ret;
                                            } else {
                                                return super.getAverageRuntime();
                                            }
                                        }

                                        @Override
                                        void crawling() {
                                            LinkCrawler.this.distribute(generation, decryptedPossibleLinks);
                                        }
                                    });
                                }
                            }
                        }
                    } finally {
                        /* close the logger */
                        if (logger instanceof ClosableLogInterface) {
                            ((ClosableLogInterface) logger).close();
                        }
                    }
                } finally {
                    if (lct != null) {
                        /* reset thread to last known used state */
                        lct.setCurrentOwner(owner);
                        lct.setCurrentLinkCrawler(previousCrawler);
                        lct.setLogger(oldLogger);
                        lct.setVerbose(oldVerbose);
                        lct.setDebug(oldDebug);
                    }
                }
            } finally {
                /* restore old ClassLoader for current Thread */
                checkFinishNotify(task);
            }
        }
    }

    private boolean isDuplicatedCrawling(final LazyCrawlerPlugin lazyC, final CrawledLink cryptedLink) {
        String url = cryptedLink.getURL();
        try {
            final URL tmp = URLHelper.createURL(url);
            final String urlDecodedPath = URLDecoder.decode(tmp.getPath(), "UTF-8");
            if (!StringUtils.equals(tmp.getPath(), urlDecodedPath)) {
                url = URLHelper.createURL(tmp.getProtocol(), tmp.getUserInfo(), tmp.getHost(), tmp.getPort(), urlDecodedPath, tmp.getQuery(), tmp.getRef());
            } else {
                if (!StringUtils.contains(url, tmp.getHost())) {
                    url = tmp.toString();
                }
            }
        } catch (Exception ignore) {
        }
        synchronized (duplicateFinderCrawler) {
            Set<String> set = duplicateFinderCrawler.get(lazyC);
            if (set == null) {
                set = new HashSet<String>();
                duplicateFinderCrawler.put(lazyC, set);
            }
            return !set.add(url);
        }
    }

    protected LinkCrawlerThread getCurrentLinkCrawlerThread() {
        final Thread currentThread = Thread.currentThread();
        if (currentThread instanceof LinkCrawlerThread) {
            return (LinkCrawlerThread) currentThread;
        } else {
            return null;
        }
    }

    protected void crawl(final LinkCrawlerGeneration generation, LazyCrawlerPlugin lazyC, final CrawledLink cryptedLink) {
        final CrawledLinkModifier parentLinkModifier = cryptedLink.getCustomCrawledLinkModifier();
        cryptedLink.setCustomCrawledLinkModifier(null);
        final BrokenCrawlerHandler brokenCrawler = cryptedLink.getBrokenCrawlerHandler();
        cryptedLink.setBrokenCrawlerHandler(null);
        if (lazyC == null || cryptedLink.getCryptedLink() == null) {
            return;
        } else if (isDuplicatedCrawling(lazyC, cryptedLink)) {
            onCrawledLinkDuplicate(cryptedLink, DUPLICATE.CRAWLER);
            return;
        } else if (this.isCrawledLinkFiltered(cryptedLink)) {
            return;
        }
        final LinkCrawlerTask task;
        if ((task = checkStartNotify(generation, "crawlPlugin:" + lazyC + "|" + cryptedLink.getURL())) != null) {
            try {
                final String[] sourceURLs = getAndClearSourceURLs(cryptedLink);
                processedLinksCounter.incrementAndGet();
                final PluginForDecrypt wplg;
                /*
                 * we want a fresh pluginClassLoader here
                 */
                try {
                    wplg = lazyC.newInstance(getPluginClassLoaderChild());
                } catch (UpdateRequiredClassNotFoundException e1) {
                    LogController.CL().log(e1);
                    return;
                }
                final AtomicReference<LinkCrawler> nextLinkCrawler = new AtomicReference<LinkCrawler>(this);
                final Browser br = wplg.createNewBrowserInstance();
                wplg.setBrowser(br);
                LogInterface oldLogger = null;
                boolean oldVerbose = false;
                boolean oldDebug = false;
                final LogInterface logger = LogController.getFastPluginLogger(wplg.getCrawlerLoggerID(cryptedLink));
                logger.info("Crawling: " + cryptedLink.getURL());
                wplg.setLogger(logger);
                wplg.init();
                /* now we run the plugin and let it find some links */
                final LinkCrawlerThread lct = getCurrentLinkCrawlerThread();
                Object owner = null;
                LinkCrawlerDistributer dist = null;
                LinkCrawler previousCrawler = null;
                List<DownloadLink> decryptedPossibleLinks = null;
                try {
                    final boolean useDelay = wplg.getDistributeDelayerMinimum() > 0;
                    final DelayedRunnable finalLinkCrawlerDistributerDelayer;
                    final List<CrawledLink> distributedLinks;
                    if (useDelay) {
                        distributedLinks = new ArrayList<CrawledLink>();
                        final int minimumDelay = Math.max(10, wplg.getDistributeDelayerMinimum());
                        int maximumDelay = wplg.getDistributeDelayerMaximum();
                        if (maximumDelay == 0) {
                            maximumDelay = -1;
                        }
                        finalLinkCrawlerDistributerDelayer = new DelayedRunnable(TIMINGQUEUE, minimumDelay, maximumDelay) {
                            @Override
                            public String getID() {
                                return "LinkCrawler";
                            }

                            @Override
                            public void delayedrun() {
                                /* we are now in IOEQ, thats why we create copy and then push work back into LinkCrawler */
                                final List<CrawledLink> linksToDistribute;
                                synchronized (distributedLinks) {
                                    if (distributedLinks.size() == 0) {
                                        return;
                                    } else {
                                        linksToDistribute = new ArrayList<CrawledLink>(distributedLinks);
                                        distributedLinks.clear();
                                    }
                                }
                                final LinkCrawlerTask innerTask;
                                if ((innerTask = checkStartNotify(generation, task.getTaskID() + "|crawlPool(1)")) != null) {
                                    /* enqueue distributing of the links */
                                    threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation, innerTask) {
                                        @Override
                                        public long getAverageRuntime() {
                                            final Long ret = getDefaultAverageRuntime();
                                            if (ret != null) {
                                                return ret;
                                            } else {
                                                return super.getAverageRuntime();
                                            }
                                        }

                                        @Override
                                        void crawling() {
                                            nextLinkCrawler.get().distribute(generation, linksToDistribute);
                                        }
                                    });
                                }
                            }
                        };
                    } else {
                        finalLinkCrawlerDistributerDelayer = null;
                        distributedLinks = null;
                    }
                    /*
                     * set LinkCrawlerDistributer in case the plugin wants to add links in realtime
                     */
                    wplg.setDistributer(dist = new LinkCrawlerDistributer() {
                        final HashSet<DownloadLink> fastDuplicateDetector = new HashSet<DownloadLink>();
                        final AtomicInteger         distributed           = new AtomicInteger(0);
                        final HashSet<DownloadLink> distribute            = new HashSet<DownloadLink>();

                        public synchronized void distribute(DownloadLink... links) {
                            if (links == null || (links.length == 0 && wplg.getDistributer() != null)) {
                                return;
                            }
                            for (final DownloadLink link : links) {
                                if (link != null && link.getPluginPatternMatcher() != null && !fastDuplicateDetector.contains(link)) {
                                    distribute.add(link);
                                }
                            }
                            if (wplg.getDistributer() != null && (distribute.size() + distributed.get()) <= 1) {
                                /**
                                 * crawler is still running, wait for finish or multiple distributed
                                 */
                                return;
                            }
                            final List<CrawledLink> possibleCryptedLinks = new ArrayList<CrawledLink>(distribute.size());
                            final boolean distributeMultipleLinks = (distribute.size() + distributed.get()) > 1;
                            final String cleanURL = cleanURL(cryptedLink.getCryptedLink().getCryptedUrl());
                            for (final DownloadLink link : distribute) {
                                if (link.getPluginPatternMatcher() != null && fastDuplicateDetector.add(link)) {
                                    distributed.incrementAndGet();
                                    if (cleanURL != null) {
                                        if (isTempDecryptedURL(link.getPluginPatternMatcher())) {
                                            /**
                                             * some plugins have same regex for hoster/decrypter, so they add decrypted.com at the end
                                             */
                                            if (distributeMultipleLinks) {
                                                if (link.getContainerUrl() == null) {
                                                    link.setContainerUrl(cleanURL);
                                                }
                                            } else {
                                                if (link.getContentUrl() == null) {
                                                    link.setContentUrl(cleanURL);
                                                }
                                            }
                                        } else {
                                            /**
                                             * this plugin returned multiple links, so we set containerURL (if not set yet)
                                             */
                                            if (distributeMultipleLinks && link.getContainerUrl() == null) {
                                                link.setContainerUrl(cleanURL);
                                            }
                                        }
                                    }
                                    final CrawledLink crawledLink = wplg.convert(link);
                                    forwardCrawledLinkInfos(cryptedLink, crawledLink, parentLinkModifier, sourceURLs, !distributeMultipleLinks);
                                    possibleCryptedLinks.add(crawledLink);
                                }
                            }
                            distribute.clear();
                            if (useDelay && wplg.getDistributer() != null) {
                                /* we delay the distribute */
                                synchronized (distributedLinks) {
                                    /* synchronized adding */
                                    distributedLinks.addAll(possibleCryptedLinks);
                                }
                                /* restart delayer to distribute links */
                                finalLinkCrawlerDistributerDelayer.run();
                            } else if (possibleCryptedLinks.size() > 0) {
                                /* we do not delay the distribute */
                                final LinkCrawlerTask innerTask;
                                if ((innerTask = checkStartNotify(generation, task.getTaskID() + "|crawlPool(2)")) != null) {
                                    /* enqueue distributing of the links */
                                    threadPool.execute(new LinkCrawlerRunnable(LinkCrawler.this, generation, innerTask) {
                                        @Override
                                        public long getAverageRuntime() {
                                            final Long ret = getDefaultAverageRuntime();
                                            if (ret != null) {
                                                return ret;
                                            } else {
                                                return super.getAverageRuntime();
                                            }
                                        }

                                        @Override
                                        void crawling() {
                                            nextLinkCrawler.get().distribute(generation, possibleCryptedLinks);
                                        }
                                    });
                                }
                            }
                        }
                    });
                    if (lct != null) {
                        /* mark thread to be used by decrypter plugin */
                        owner = lct.getCurrentOwner();
                        lct.setCurrentOwner(wplg);
                        previousCrawler = lct.getCurrentLinkCrawler();
                        lct.setCurrentLinkCrawler(this);
                        /* save old logger/states */
                        oldLogger = lct.getLogger();
                        oldDebug = lct.isDebug();
                        oldVerbose = lct.isVerbose();
                        /* set new logger and set verbose/debug true */
                        lct.setLogger(logger);
                        lct.setVerbose(true);
                        lct.setDebug(true);
                    }
                    final long startTime = Time.systemIndependentCurrentJVMTimeMillis();
                    try {
                        wplg.setCrawler(this);
                        wplg.setLinkCrawlerGeneration(generation);
                        final LinkCrawler pluginNextLinkCrawler = wplg.getCustomNextCrawler();
                        if (pluginNextLinkCrawler != null) {
                            nextLinkCrawler.set(pluginNextLinkCrawler);
                        }
                        decryptedPossibleLinks = wplg.decryptLink(cryptedLink);
                        /* remove distributer from plugin to process remaining/returned links */
                        wplg.setDistributer(null);
                        if (finalLinkCrawlerDistributerDelayer != null) {
                            finalLinkCrawlerDistributerDelayer.setDelayerEnabled(false);
                            /* make sure we dont have any unprocessed delayed Links */
                            finalLinkCrawlerDistributerDelayer.delayedrun();
                        }
                        if (decryptedPossibleLinks != null) {
                            /* distribute remaining/returned links */
                            dist.distribute(decryptedPossibleLinks.toArray(new DownloadLink[decryptedPossibleLinks.size()]));
                        }
                        /* in case we return normally, clear the logger */
                        if (logger instanceof ClearableLogInterface) {
                            ((ClearableLogInterface) logger).clear();
                        }
                    } finally {
                        /* close the logger */
                        wplg.setLinkCrawlerGeneration(null);
                        wplg.setCurrentLink(null);
                        final long endTime = Time.systemIndependentCurrentJVMTimeMillis() - startTime;
                        lazyC.updateCrawlRuntime(endTime);
                        if (logger instanceof ClosableLogInterface) {
                            ((ClosableLogInterface) logger).close();
                        }
                    }
                } finally {
                    if (lct != null) {
                        /* reset thread to last known used state */
                        lct.setCurrentOwner(owner);
                        lct.setCurrentLinkCrawler(previousCrawler);
                        lct.setLogger(oldLogger);
                        lct.setVerbose(oldVerbose);
                        lct.setDebug(oldDebug);
                    }
                }
                if (decryptedPossibleLinks == null) {
                    this.handleBrokenCrawledLink(cryptedLink);
                }
                if (brokenCrawler != null) {
                    try {
                        brokenCrawler.brokenCrawler(cryptedLink, this);
                    } catch (final Throwable e) {
                        LogController.CL().log(e);
                    }
                }
            } finally {
                /* restore old ClassLoader for current Thread */
                checkFinishNotify(task);
            }
        }
    }

    public java.util.List<CrawledLink> getCrawledLinks() {
        return crawledLinks;
    }

    public java.util.List<CrawledLink> getFilteredLinks() {
        return filteredLinks;
    }

    public java.util.List<CrawledLink> getBrokenLinks() {
        return brokenLinks;
    }

    public java.util.List<CrawledLink> getUnhandledLinks() {
        return unhandledLinks;
    }

    protected void handleBrokenCrawledLink(CrawledLink link) {
        this.brokenLinksCounter.incrementAndGet();
        getHandler().handleBrokenLink(link);
    }

    protected void handleUnhandledCryptedLink(CrawledLink link) {
        this.unhandledLinksCounter.incrementAndGet();
        getHandler().handleUnHandledLink(link);
    }

    private String getContentURL(final CrawledLink link) {
        final DownloadLink downloadLink = link.getDownloadLink();
        final PluginForHost plugin = downloadLink.getDefaultPlugin();
        if (downloadLink != null && plugin != null) {
            final String pluginURL = downloadLink.getPluginPatternMatcher();
            final Iterator<CrawledLink> it = link.iterator();
            while (it.hasNext()) {
                final CrawledLink next = it.next();
                if (next == link) {
                    continue;
                }
                if (next.getDownloadLink() != null || next.getCryptedLink() == null) {
                    final String nextURL = cleanURL(next.getURL());
                    if (nextURL != null && !StringUtils.equals(pluginURL, nextURL)) {
                        final String[] hits = new Regex(nextURL, plugin.getSupportedLinks()).getColumn(-1);
                        if (hits != null) {
                            try {
                                if (hits.length == 1 && hits[0] != null && plugin.isValidURL(hits[0]) && !StringUtils.equals(pluginURL, hits[0]) && new URL(hits[0]).getPath().length() > 1) {
                                    return hits[0];
                                } else {
                                    return null;
                                }
                            } catch (IOException e) {
                            }
                        }
                    }
                    if (next.getDownloadLink() != null) {
                        continue;
                    }
                }
                break;
            }
        }
        return null;
    }

    private String getOriginURL(final CrawledLink link) {
        final DownloadLink downloadLink = link.getDownloadLink();
        if (downloadLink != null) {
            final String pluginURL = downloadLink.getPluginPatternMatcher();
            final Iterator<CrawledLink> it = link.iterator();
            String originURL = null;
            while (it.hasNext()) {
                final CrawledLink next = it.next();
                if (next == link || (next.getDownloadLink() != null && next.getDownloadLink().getUrlProtection() != UrlProtection.UNSET)) {
                    originURL = null;
                    continue;
                }
                final String nextURL = cleanURL(next.getURL());
                if (nextURL != null && !StringUtils.equals(pluginURL, nextURL)) {
                    originURL = nextURL;
                }
            }
            return originURL;
        }
        return null;
    }

    protected void postprocessFinalCrawledLink(CrawledLink link) {
        final DownloadLink downloadLink = link.getDownloadLink();
        if (downloadLink != null) {
            final HashSet<String> knownURLs = new HashSet<String>();
            knownURLs.add(downloadLink.getPluginPatternMatcher());
            if (downloadLink.getContentUrl() != null) {
                if (StringUtils.equals(downloadLink.getPluginPatternMatcher(), downloadLink.getContentUrl())) {
                    downloadLink.setContentUrl(null);
                }
                knownURLs.add(downloadLink.getContentUrl());
            } else if (true || downloadLink.getContainerUrl() == null) {
                /**
                 * remove true in case we don't want a contentURL when containerURL is already set
                 */
                final String contentURL = getContentURL(link);
                if (contentURL != null && knownURLs.add(contentURL)) {
                    downloadLink.setContentUrl(contentURL);
                }
            }
            if (downloadLink.getContainerUrl() != null) {
                /**
                 * containerURLs are only set by crawl or crawlDeeper or manually
                 */
                knownURLs.add(downloadLink.getContainerUrl());
            }
            if (downloadLink.getOriginUrl() != null) {
                knownURLs.add(downloadLink.getOriginUrl());
            } else {
                final String originURL = getOriginURL(link);
                if (originURL != null && knownURLs.add(originURL)) {
                    downloadLink.setOriginUrl(originURL);
                }
            }
            if (StringUtils.equals(downloadLink.getOriginUrl(), downloadLink.getContainerUrl())) {
                downloadLink.setContainerUrl(null);
            }
            if (downloadLink.getReferrerUrl() == null) {
                final String referrerURL = getReferrerUrl(link);
                if (referrerURL != null && knownURLs.add(referrerURL)) {
                    downloadLink.setReferrerUrl(referrerURL);
                }
            }
        }
    }

    public static boolean isTempDecryptedURL(final String url) {
        if (url != null) {
            final String host = Browser.getHost(url, true);
            return StringUtils.containsIgnoreCase(host, "decrypted") || StringUtils.containsIgnoreCase(host, "yt.not.allowed");
        }
        return false;
    }

    public static String cleanURL(String cUrl) {
        final boolean isSupportedProtocol = HTMLParser.isSupportedProtocol(cUrl);
        if (isSupportedProtocol) {
            final String host = Browser.getHost(cUrl, true);
            if (!StringUtils.containsIgnoreCase(host, "decrypted") && !StringUtils.containsIgnoreCase(host, "dummydirect.jdownloader.org") && !StringUtils.containsIgnoreCase(host, "dummycnl.jdownloader.org") && !StringUtils.containsIgnoreCase(host, "yt.not.allowed")) {
                if (cUrl.startsWith("http://") || cUrl.startsWith("https://") || cUrl.startsWith("ftp://") || cUrl.startsWith("file:/")) {
                    return cUrl;
                } else if (cUrl.startsWith("directhttp://")) {
                    return cUrl.substring("directhttp://".length());
                } else if (cUrl.startsWith("httpviajd://")) {
                    return "http://".concat(cUrl.substring("httpviajd://".length()));
                } else if (cUrl.startsWith("httpsviajd://")) {
                    return "https://".concat(cUrl.substring("httpsviajd://".length()));
                } else if (cUrl.startsWith("ftpviajd://")) {
                    return "ftp://".concat(cUrl.substring("ftpviajd://".length()));
                }
            }
        }
        return null;
    }

    protected void handleFinalCrawledLink(LinkCrawlerGeneration generation, CrawledLink link) {
        if (link != null) {
            if (link.getMatchingRule() != null && link.getDownloadLink() != null) {
                link.getDownloadLink().setProperty("lcrID", link.getMatchingRule().getId());
            }
            final CrawledLink origin = link.getOriginLink();
            if (link.getCreated() == -1) {
                link.setCreated(getCreated());
                final CrawledLinkModifier customModifier = link.getCustomCrawledLinkModifier();
                if (customModifier != null) {
                    link.setCustomCrawledLinkModifier(null);
                    try {
                        customModifier.modifyCrawledLink(link);
                    } catch (final Throwable e) {
                        LogController.CL().log(e);
                    }
                }
                postprocessFinalCrawledLink(link);
                /* clean up some references */
                link.setBrokenCrawlerHandler(null);
                link.setUnknownHandler(null);
            }
            if (isDoDuplicateFinderFinalCheck()) {
                /* specialHandling: Crypted A - > B - > Final C , and A equals C */
                // if link comes from flashgot, origin might be null
                final boolean specialHandling = origin != null && (origin != link) && (StringUtils.equals(origin.getLinkID(), link.getLinkID()));
                if (!specialHandling) {
                    CrawledLink existing = null;
                    synchronized (duplicateFinderFinal) {
                        final String key = Encoding.urlDecode(link.getLinkID(), false);
                        existing = duplicateFinderFinal.get(key);
                        if (existing == null) {
                            duplicateFinderFinal.put(key, link);
                        }
                    }
                    if (existing != null) {
                        final PluginForHost hPlugin = link.gethPlugin();
                        if (hPlugin == null || hPlugin.onLinkCrawlerDupeFilterEnabled(existing, link)) {
                            onCrawledLinkDuplicate(link, DUPLICATE.FINAL);
                            return;
                        }
                    }
                }
            }
            enqueueFinalCrawledLink(generation, link);
        }
    }

    protected void enqueueFinalCrawledLink(LinkCrawlerGeneration generation, CrawledLink link) {
        if (isCrawledLinkFiltered(link) == false) {
            /* link is not filtered, so we can process it normally */
            crawledLinksCounter.incrementAndGet();
            getHandler().handleFinalLink(link);
        }
    }

    protected boolean isCrawledLinkFiltered(CrawledLink link) {
        final LinkCrawler parent = getParent();
        if (parent != null && getFilter() != parent.getFilter()) {
            if (parent.isCrawledLinkFiltered(link)) {
                return true;
            }
        }
        if (getFilter().dropByUrl(link)) {
            filteredLinksCounter.incrementAndGet();
            getHandler().handleFilteredLink(link);
            return true;
        } else {
            return false;
        }
    }

    protected void onCrawledLinkDuplicate(CrawledLink link, DUPLICATE duplicate) {
    }

    public int getCrawledLinksFoundCounter() {
        return crawledLinksCounter.get();
    }

    public int getFilteredLinksFoundCounter() {
        return filteredLinksCounter.get();
    }

    public int getBrokenLinksFoundCounter() {
        return brokenLinksCounter.get();
    }

    public int getUnhandledLinksFoundCounter() {
        return unhandledLinksCounter.get();
    }

    public int getProcessedLinksCounter() {
        return crawledLinksCounter.get() + filteredLinksCounter.get() + brokenLinksCounter.get() + processedLinksCounter.get();
    }

    public LinkCrawlerDeepInspector defaultDeepInspector() {
        return new LinkCrawlerDeepInspector() {
            @Override
            public List<CrawledLink> deepInspect(LinkCrawler lc, final LinkCrawlerGeneration generation, Browser br, URLConnectionAdapter urlConnection, CrawledLink link) throws Exception {
                final int limit = Math.max(1 * 1024 * 1024, CONFIG.getDeepDecryptLoadLimit());
                if (br != null) {
                    br.setLoadLimit(limit);
                }
                final LinkCrawlerRule rule = link.getMatchingRule();
                if (rule == null && !urlConnection.isContentDisposition()) {
                    final boolean hasContentType = urlConnection.getHeaderField(HTTPConstants.HEADER_REQUEST_CONTENT_TYPE) != null;
                    if (urlConnection.getRequest().getLocation() == null && urlConnection.getResponseCode() == 200 && !isTextContent(urlConnection) || urlConnection.getCompleteContentLength() > limit) {
                        if (!hasContentType) {
                            try {
                                br.followConnection();
                                if (br.containsHTML("<!DOCTYPE html>") || (br.containsHTML("</html") && br.containsHTML("<html"))) {
                                    return null;
                                }
                            } catch (final IOException e) {
                                final LogInterface log = br.getLogger();
                                if (log != null) {
                                    log.log(e);
                                }
                            }
                        }
                        urlConnection.disconnect();
                        final ArrayList<CrawledLink> ret = new ArrayList<CrawledLink>();
                        final CrawledLink direct = createDirectHTTPCrawledLink(link, null, urlConnection);
                        if (direct != null) {
                            ret.add(direct);
                        }
                        return ret;
                    }
                }
                if (looksLikeDownloadableContent(urlConnection)) {
                    if (rule != null && RULE.DEEPDECRYPT.equals(rule.getRule()) && isTextContent(urlConnection)) {
                        br.followConnection();
                        return null;
                    }
                    urlConnection.disconnect();
                    final ArrayList<CrawledLink> ret = new ArrayList<CrawledLink>();
                    final CrawledLink direct = createDirectHTTPCrawledLink(link, null, urlConnection);
                    if (direct != null) {
                        ret.add(direct);
                    }
                    return ret;
                } else {
                    br.followConnection();
                    return null;
                }
            }
        };
    }

    public LinkCrawlerHandler defaultHandlerFactory() {
        return new LinkCrawlerHandler() {
            public void handleFinalLink(CrawledLink link) {
                synchronized (crawledLinks) {
                    crawledLinks.add(link);
                }
            }

            public void handleFilteredLink(CrawledLink link) {
                synchronized (filteredLinks) {
                    filteredLinks.add(link);
                }
            }

            @Override
            public void handleBrokenLink(CrawledLink link) {
                synchronized (brokenLinks) {
                    brokenLinks.add(link);
                }
            }

            @Override
            public void handleUnHandledLink(CrawledLink link) {
                synchronized (unhandledLinks) {
                    unhandledLinks.add(link);
                }
            }
        };
    }

    public LinkCrawlerFilter defaultFilterFactory() {
        return new LinkCrawlerFilter() {
            public boolean dropByUrl(CrawledLink link) {
                return false;
            }

            public boolean dropByFileProperties(CrawledLink link) {
                return false;
            };
        };
    }

    public void setFilter(LinkCrawlerFilter filter) {
        if (filter == null) {
            throw new IllegalArgumentException("filter is null");
        }
        this.filter = filter;
    }

    public void setHandler(LinkCrawlerHandler handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler is null");
        }
        this.handler = handler;
    }

    public void setDeepInspector(LinkCrawlerDeepInspector deepInspector) {
        if (deepInspector == null) {
            throw new IllegalArgumentException("deepInspector is null");
        }
        this.deepInspector = deepInspector;
    }

    public LinkCrawlerFilter getFilter() {
        return filter;
    }

    public LinkCrawlerHandler getHandler() {
        return this.handler;
    }

    public LinkCrawlerDeepInspector getDeepInspector() {
        return this.deepInspector;
    }
}
