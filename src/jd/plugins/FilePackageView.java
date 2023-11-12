package jd.plugins;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.swing.Icon;

import jd.controlling.downloadcontroller.DownloadWatchDog;
import jd.controlling.downloadcontroller.SingleDownloadController;
import jd.controlling.packagecontroller.ChildrenView;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.download.DownloadInterface;

import org.appwork.storage.config.JsonConfig;
import org.appwork.utils.StringUtils;
import org.appwork.utils.Time;
import org.appwork.utils.os.CrossSystem;
import org.jdownloader.DomainInfo;
import org.jdownloader.controlling.DownloadLinkView;
import org.jdownloader.extensions.extraction.ExtractionProgress;
import org.jdownloader.extensions.extraction.ExtractionStatus;
import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.gui.views.components.packagetable.PackageControllerTableModelData.PackageControllerTableModelDataPackage;
import org.jdownloader.gui.views.downloads.columns.AvailabilityColumn;
import org.jdownloader.images.AbstractIcon;
import org.jdownloader.plugins.ConditionalSkipReason;
import org.jdownloader.plugins.FinalLinkState;
import org.jdownloader.plugins.MirrorLoading;
import org.jdownloader.plugins.SkipReason;
import org.jdownloader.plugins.TimeOutCondition;
import org.jdownloader.settings.GeneralSettings;
import org.jdownloader.settings.GraphicalUserInterfaceSettings;

public class FilePackageView extends ChildrenView<FilePackage, DownloadLink> {
    private static class LinkInfo {
        private long         bytesTotal = -1;
        private long         bytesDone  = -1;
        private long         speed      = -1;
        private boolean      enabled    = true;
        private DownloadLink link;
    }

    private final FilePackage              pkg;
    protected volatile long                lastUpdateTimestamp            = -1;
    protected volatile boolean             lastRunningState               = false;
    protected volatile long                finishedDate                   = -1;
    protected volatile long                estimatedETA                   = -1;
    private volatile int                   offline                        = 0;
    private volatile int                   online                         = 0;
    private final AtomicLong               updatesRequired                = new AtomicLong(0);
    private volatile long                  updatesDone                    = -1;
    private volatile String                availabilityColumnString       = null;
    private volatile ChildrenAvailablility availability                   = ChildrenAvailablility.UNKNOWN;
    private volatile int                   count                          = 0;
    protected static final long            GUIUPDATETIMEOUT               = JsonConfig.create(GraphicalUserInterfaceSettings.class).getDownloadViewRefresh();
    protected static final boolean         FORCED_MIRROR_CASE_INSENSITIVE = CrossSystem.isWindows() || JsonConfig.create(GeneralSettings.class).isForceMirrorDetectionCaseInsensitive();

    public boolean isEnabled() {
        return enabledCount > 0;
    }

    /**
     * This constructor is protected. do not use this class outside the FilePackage
     *
     * @param fp
     */
    public FilePackageView(FilePackage pkg) {
        this.pkg = pkg;
    }

    private volatile DomainInfo[] domains          = new DomainInfo[0];
    private volatile long         size             = 0;
    private volatile int          finalCount       = 0;
    private volatile int          unknownFileSizes = 0;

    public int getUnknownFileSizes() {
        return unknownFileSizes;
    }

    public int getFinalCount() {
        return finalCount;
    }

    private long                  done = 0;
    private int                   enabledCount;
    private PluginStateCollection pluginStates;

    public PluginStateCollection getPluginStates() {
        return pluginStates;
    }

    public DomainInfo[] getDomainInfos() {
        return domains;
    }

    public long getSize() {
        return size;
    }

    public long getDone() {
        return done;
    }

    public long getETA() {
        return estimatedETA;
    }

    public boolean isFinished() {
        final long ret = finishedDate;
        return ret > 0 || ret == Integer.MIN_VALUE;
    }

    public int getDisabledCount() {
        return Math.max(0, size() - enabledCount);
    }

    public long getFinishedDate() {
        final long ret = finishedDate;
        if (ret <= 0) {
            return -1;
        } else {
            return ret;
        }
    }

    private String commonSourceUrl;

    @Override
    public FilePackageView aggregate() {
        final long lupdatesRequired = updatesRequired.get();
        lastUpdateTimestamp = Time.systemIndependentCurrentJVMTimeMillis();
        synchronized (this) {
            final Temp tmp = new Temp();
            /* this is called for repaint, so only update values that could have changed for existing items */
            final PackageControllerTableModelDataPackage<FilePackage, DownloadLink> tableModelDataPackage = getTableModelDataPackage();
            if (tableModelDataPackage != null) {
                for (final DownloadLink child : tableModelDataPackage.getVisibleChildren()) {
                    addLinkToTemp(tmp, child);
                }
            } else {
                final boolean readL = pkg.getModifyLock().readLock();
                try {
                    for (final DownloadLink link : pkg.getChildren()) {
                        addLinkToTemp(tmp, link);
                    }
                } finally {
                    pkg.getModifyLock().readUnlock(readL);
                }
            }
            writeTempToFields(tmp);
            updatesDone = lupdatesRequired;
            if (domains.length != tmp.domains.size() || !tmp.domains.values().containsAll(Arrays.asList(domains))) {
                final ArrayList<DomainInfo> lst = new ArrayList<DomainInfo>(tmp.domains.values());
                if (lst.size() > 1) {
                    Collections.sort(lst, DOMAININFOCOMPARATOR);
                }
                domains = lst.toArray(new DomainInfo[tmp.domains.size()]);
            }
        }
        return this;
    }

    private class Temp {
        private int                                   newUnknownFileSizes = 0;
        private int                                   newFinalCount       = 0;
        private long                                  newFinishedDate     = -1;
        private int                                   newOffline          = 0;
        private int                                   newOnline           = 0;
        private int                                   newEnabledCount     = 0;
        private int                                   count               = 0;
        private final HashMap<String, LinkInfo>       linkInfos           = new HashMap<String, LinkInfo>();
        private final HashMap<String, DomainInfo>     domains             = new HashMap<String, DomainInfo>();
        private boolean                               allFinished         = true;
        private String                                sameSource          = null;
        private boolean                               sameSourceFullUrl   = true;
        private final HashMap<Object, PluginState<?>> pluginStates        = new HashMap<Object, PluginState<?>>();
    }

    public class PluginState<T> {
        protected String  description;
        protected Icon    stateIcon;
        protected final T cause;

        public String getDescription() {
            return description;
        }

        public Icon getIcon() {
            return stateIcon;
        }

        public T getCause() {
            return cause;
        }

        protected PluginState(T cause, String message, Icon icon2) {
            this.cause = cause;
            this.description = message;
            this.stateIcon = icon2;
        }
    }

    public class ExtractionPluginState extends PluginState<ExtractionStatus> {
        protected final Map<String, DownloadLink> archives = new HashMap<String, DownloadLink>();

        protected ExtractionPluginState(String message, Icon icon2) {
            super(null, message, icon2);
        }
    }

    private final static AbstractIcon          EXTRACTICONOK        = new AbstractIcon(IconKey.ICON_EXTRACT_OK, 16);
    private final static AbstractIcon          EXTRACTICONERROR     = new AbstractIcon(IconKey.ICON_EXTRACT_ERROR, 16);
    private final static AbstractIcon          EXTRACTICONSTART     = new AbstractIcon(IconKey.ICON_EXTRACT_RUN, 16);
    private final static AbstractIcon          FALSEICON            = new AbstractIcon(IconKey.ICON_FALSE, 16);
    public final static Comparator<DomainInfo> DOMAININFOCOMPARATOR = new Comparator<DomainInfo>() {
        @Override
        public int compare(DomainInfo o1, DomainInfo o2) {
            return o1.getTld().compareTo(o2.getTld());
        }
    };

    protected void writeTempToFields(final Temp tmp) {
        long size = -1;
        long done = 0;
        long speed = -1;
        long maxSingleETA = -1;
        boolean atLeastOneEnabled = false;
        for (final LinkInfo linkInfo : tmp.linkInfos.values()) {
            if (linkInfo.enabled) {
                atLeastOneEnabled = true;
                break;
            }
        }
        for (final LinkInfo linkInfo : tmp.linkInfos.values()) {
            if (atLeastOneEnabled == true && linkInfo.enabled == false) {
                continue;
            }
            if (linkInfo.speed >= 0) {
                if (speed == -1) {
                    speed = 0;
                }
                speed += linkInfo.speed;
            }
            // todo status abfangen, skip final/skipped
            if (linkInfo.bytesTotal >= 0) {
                if (size == -1) {
                    size = 0;
                }
                size += linkInfo.bytesTotal;
            } else {
                tmp.newUnknownFileSizes++;
            }
            if (linkInfo.bytesDone >= 0) {
                done += linkInfo.bytesDone;
            }
            final long bytesLeft = Math.max(-1, linkInfo.bytesTotal - linkInfo.bytesDone);
            if (linkInfo.speed > 0 && bytesLeft >= 0) {
                final long singleETA = bytesLeft / linkInfo.speed;
                if (singleETA > maxSingleETA) {
                    maxSingleETA = singleETA;
                }
            }
        }
        this.count = tmp.count;
        this.done = done;
        this.size = size;
        this.finalCount = tmp.newFinalCount;
        this.unknownFileSizes = tmp.newUnknownFileSizes;
        this.enabledCount = tmp.newEnabledCount;
        if (tmp.allFinished && atLeastOneEnabled) {
            /* all links have reached finished state */
            if (tmp.newFinishedDate <= 0) {
                /* special handling for manual *mark as finished*, does not set finished date */
                this.finishedDate = Integer.MIN_VALUE;
            } else {
                this.finishedDate = tmp.newFinishedDate;
            }
        } else {
            /* not all have finished */
            this.finishedDate = -1;
        }
        if (speed >= 0) {
            this.lastRunningState = true;
            if (size >= 0 && speed > 0) {
                /* we could calc an ETA because at least one filesize is known */
                final long bytesLeft = Math.max(-1, size - done);
                final long packageETA = bytesLeft / speed;
                this.estimatedETA = Math.max(maxSingleETA, packageETA);
            } else {
                /* no filesize is known, we use Integer.Min_value to signal this */
                this.estimatedETA = Integer.MIN_VALUE;
            }
        } else {
            /* no download running */
            this.estimatedETA = -1;
            this.lastRunningState = false;
        }
        if (!tmp.sameSourceFullUrl) {
            tmp.sameSource += "[...]";
        }
        this.commonSourceUrl = tmp.sameSource;
        this.pluginStates = new PluginStateCollection(tmp.pluginStates.values());
        this.offline = tmp.newOffline;
        this.online = tmp.newOnline;
        this.availability = updateAvailability(tmp);
        this.availabilityColumnString = _GUI.T.AvailabilityColumn_getStringValue_object_(tmp.newOnline, tmp.count);
    }

    public final ChildrenAvailablility updateAvailability(Temp tmp) {
        final int count = tmp.count;
        final int online = tmp.newOnline;
        final int offline = tmp.newOffline;
        if (online > 0 && online == count) {
            return ChildrenAvailablility.ONLINE;
        } else if (offline > 0 && offline == count) {
            return ChildrenAvailablility.OFFLINE;
        } else if ((offline == 0 && online == 0) || (online == 0 && offline > 0)) {
            return ChildrenAvailablility.UNKNOWN;
        } else {
            return ChildrenAvailablility.MIXED;
        }
    }

    public String getCommonSourceUrl() {
        return commonSourceUrl;
    }

    private ConditionalSkipReason getConditionalSkipReason(DownloadLink link) {
        final ConditionalSkipReason conditionalSkipReason = link.getConditionalSkipReason();
        if (conditionalSkipReason == null || conditionalSkipReason.isConditionReached()) {
            return null;
        } else if (conditionalSkipReason instanceof MirrorLoading) {
            /* we dont have to handle this, as another link is already downloading */
            return null;
        } else {
            return conditionalSkipReason;
        }
    }

    private final static WeakHashMap<DomainInfo, WeakHashMap<Icon, Icon>> ICONCACHE = new WeakHashMap<DomainInfo, WeakHashMap<Icon, Icon>>();

    private Icon getFavIcon(DomainInfo domainInfo, Icon icon) {
        synchronized (ICONCACHE) {
            WeakHashMap<Icon, Icon> cache = ICONCACHE.get(domainInfo);
            if (cache == null) {
                cache = new WeakHashMap<Icon, Icon>();
                ICONCACHE.put(domainInfo, cache);
            }
            Icon ret = cache.get(icon);
            if (ret == null) {
                ret = new FavitIcon(icon, domainInfo);
                cache.put(icon, ret);
            }
            return ret;
        }
    }

    protected void addLinkToTemp(Temp tmp, final DownloadLink link) {
        tmp.count++;
        final DomainInfo domainInfo = link.getDomainInfo();
        tmp.domains.put(domainInfo.getTld(), domainInfo);
        final DownloadLinkView view = link.getView();
        String sourceUrl = view.getDisplayUrl();
        if (sourceUrl != null) {
            tmp.sameSource = StringUtils.getCommonalities(tmp.sameSource, sourceUrl);
            tmp.sameSourceFullUrl = tmp.sameSourceFullUrl && tmp.sameSource.equals(sourceUrl);
        }
        if (AvailableStatus.FALSE == link.getAvailableStatus()) {
            // offline
            tmp.newOffline++;
        } else if (AvailableStatus.TRUE == link.getAvailableStatus()) {
            // online
            tmp.newOnline++;
        }
        String id = null;
        PluginState<?> ps = null;
        final PluginProgress prog = link.getPluginProgress();
        if (prog != null) {
            if (!(prog instanceof ExtractionProgress)) {
                final Icon icon = prog.getIcon(this);
                if (icon != null) {
                    id = prog.getClass().getName().concat(link.getHost());
                    if (!tmp.pluginStates.containsKey(id)) {
                        final String message = prog.getMessage(FilePackageView.this);
                        if (message != null) {
                            ps = new PluginState<PluginProgress>(prog, null, null) {
                                String msg = null;

                                @Override
                                public PluginProgress getCause() {
                                    final PluginProgress ret = super.getCause();
                                    if (link.getPluginProgress() == ret) {
                                        return ret;
                                    } else {
                                        return null;
                                    }
                                }

                                @Override
                                public Icon getIcon() {
                                    if (stateIcon == null) {
                                        stateIcon = getFavIcon(domainInfo, icon);
                                    }
                                    return stateIcon;
                                }

                                @Override
                                public String getDescription() {
                                    if (msg == null) {
                                        msg = message + " (" + domainInfo.getTld() + ")";
                                    }
                                    return msg;
                                }
                            };
                            tmp.pluginStates.put(id, ps);
                        }
                    }
                }
            }
        }
        final ConditionalSkipReason conditionalSkipReason = getConditionalSkipReason(link);
        if (conditionalSkipReason != null) {
            final Icon icon = conditionalSkipReason.getIcon(this, link);
            if (icon != null) {
                id = conditionalSkipReason.getClass().getName().concat(link.getHost());
                if (!tmp.pluginStates.containsKey(id)) {
                    final String message = conditionalSkipReason.getMessage(this, link);
                    if (message != null) {
                        ps = new PluginState<ConditionalSkipReason>(conditionalSkipReason, null, null) {
                            @Override
                            public ConditionalSkipReason getCause() {
                                final ConditionalSkipReason ret = super.getCause();
                                if (getConditionalSkipReason(link) == ret) {
                                    return ret;
                                } else {
                                    return null;
                                }
                            }

                            @Override
                            public Icon getIcon() {
                                if (stateIcon == null) {
                                    stateIcon = getFavIcon(domainInfo, icon);
                                }
                                return stateIcon;
                            }

                            @Override
                            public String getDescription() {
                                if (description == null) {
                                    description = message + " (" + domainInfo.getTld() + ")";
                                }
                                return description;
                            }
                        };
                        tmp.pluginStates.put(id, ps);
                    }
                }
            }
        }
        final SkipReason skipReason = link.getSkipReason();
        if (skipReason != null) {
            id = skipReason.name();
            if (!tmp.pluginStates.containsKey(id)) {
                ps = new PluginState<SkipReason>(skipReason, null, null) {
                    @Override
                    public String getDescription() {
                        return skipReason.getExplanation(this);
                    };

                    @Override
                    public SkipReason getCause() {
                        final SkipReason ret = super.getCause();
                        if (link.getSkipReason() == ret) {
                            return ret;
                        } else {
                            return null;
                        }
                    }

                    @Override
                    public Icon getIcon() {
                        if (stateIcon == null) {
                            stateIcon = skipReason.getIcon(this, 18);
                        }
                        return stateIcon;
                    };
                };
                tmp.pluginStates.put(id, ps);
            }
        }
        final FinalLinkState finalLinkState = link.getFinalLinkState();
        if (finalLinkState != null) {
            // if (FinalLinkState.CheckFailed(finalLinkState)) {
            switch (finalLinkState) {
            case FAILED:
            case FAILED_CRC32:
            case FAILED_EXISTS:
            case FAILED_FATAL:
            case FAILED_MD5:
            case FAILED_SHA1:
            case FAILED_SHA256:
            case OFFLINE:
            case PLUGIN_DEFECT:
                id = "error".concat(link.getHost());
                if (!tmp.pluginStates.containsKey(id)) {
                    ps = new PluginState<FinalLinkState>(finalLinkState, null, null) {
                        @Override
                        public FinalLinkState getCause() {
                            final FinalLinkState ret = super.getCause();
                            if (link.getFinalLinkState() == ret) {
                                return ret;
                            } else {
                                return null;
                            }
                        }

                        @Override
                        public Icon getIcon() {
                            if (stateIcon == null) {
                                stateIcon = getFavIcon(domainInfo, FALSEICON);
                            }
                            return stateIcon;
                        }

                        @Override
                        public String getDescription() {
                            if (description == null) {
                                description = _GUI.T.FilePackageView_addLinkToTemp_downloaderror_() + " (" + domainInfo.getTld() + ")";
                            }
                            return description;
                        }
                    };
                    tmp.pluginStates.put(id, ps);
                }
                break;
            case FINISHED:
            case FINISHED_MIRROR:
            case FINISHED_CRC32:
            case FINISHED_MD5:
            case FINISHED_SHA1:
            case FINISHED_SHA224:
            case FINISHED_SHA256:
            case FINISHED_SHA384:
            case FINISHED_SHA512:
                break;
            }
            // }
            final ExtractionStatus extractionStatus = link.getExtractionStatus();
            if (extractionStatus != null) {
                final String archiveID = link.getArchiveID();
                if (StringUtils.isNotEmpty(archiveID)) {
                    switch (extractionStatus) {
                    case ERROR:
                    case ERROR_PW:
                    case ERROR_CRC:
                    case ERROR_NOT_ENOUGH_SPACE:
                    case ERRROR_FILE_NOT_FOUND:
                        if (extractionStatus.getExplanation() != null) {
                            id = "extractError:" + extractionStatus.name();
                            ps = tmp.pluginStates.get(id);
                            if (ps == null) {
                                ps = new ExtractionPluginState(null, EXTRACTICONERROR) {
                                    @Override
                                    public String getDescription() {
                                        if (description == null) {
                                            final StringBuilder sb = new StringBuilder();
                                            sb.append(extractionStatus.getExplanation());
                                            sb.append(": ");
                                            int i = 0;
                                            for (final DownloadLink archive : archives.values()) {
                                                if (i > 0) {
                                                    sb.append("\r\n");
                                                }
                                                sb.append(archive.getName());
                                                i++;
                                            }
                                            description = sb.toString();
                                        }
                                        return description;
                                    };
                                };
                                tmp.pluginStates.put(id, ps);
                                final ExtractionPluginState eps = (ExtractionPluginState) ps;
                                eps.archives.put(archiveID, link);
                            } else {
                                final ExtractionPluginState eps = (ExtractionPluginState) ps;
                                if (!eps.archives.containsKey(archiveID)) {
                                    eps.archives.put(archiveID, link);
                                }
                            }
                        }
                        break;
                    case SUCCESSFUL:
                        if (extractionStatus.getExplanation() != null) {
                            id = "extractSuccess";
                            ps = tmp.pluginStates.get(id);
                            if (ps == null) {
                                ps = new ExtractionPluginState(null, EXTRACTICONOK) {
                                    @Override
                                    public ExtractionStatus getCause() {
                                        for (final DownloadLink link : archives.values()) {
                                            if (link.getExtractionStatus() != ExtractionStatus.SUCCESSFUL) {
                                                return null;
                                            }
                                        }
                                        return ExtractionStatus.SUCCESSFUL;
                                    }

                                    @Override
                                    public String getDescription() {
                                        if (description == null) {
                                            final StringBuilder sb = new StringBuilder();
                                            sb.append(extractionStatus.getExplanation());
                                            sb.append(": ");
                                            int i = 0;
                                            for (final DownloadLink archive : archives.values()) {
                                                if (i > 0) {
                                                    sb.append("\r\n");
                                                }
                                                sb.append(archive.getName());
                                                i++;
                                            }
                                            description = sb.toString();
                                        }
                                        return description;
                                    };
                                };
                                tmp.pluginStates.put(id, ps);
                                final ExtractionPluginState eps = (ExtractionPluginState) ps;
                                eps.archives.put(archiveID, link);
                            } else {
                                final ExtractionPluginState eps = (ExtractionPluginState) ps;
                                if (!eps.archives.containsKey(archiveID)) {
                                    eps.archives.put(archiveID, link);
                                }
                            }
                        }
                        break;
                    case RUNNING:
                        id = "ExtractionRunning".concat(archiveID);
                        final PluginProgress prog2 = link.getPluginProgress();
                        ps = null;
                        if (prog2 != null) {
                            if (prog2 instanceof ExtractionProgress) {
                                if (!tmp.pluginStates.containsKey(id)) {
                                    final String message = prog2.getMessage(FilePackageView.this);
                                    if (message != null) {
                                        ps = new PluginState<PluginProgress>(prog2, null, EXTRACTICONSTART) {
                                            @Override
                                            public PluginProgress getCause() {
                                                final PluginProgress ret = super.getCause();
                                                if (link.getPluginProgress() == ret) {
                                                    return ret;
                                                } else {
                                                    return null;
                                                }
                                            }

                                            @Override
                                            public String getDescription() {
                                                if (description == null) {
                                                    description = message + " (" + link.getName() + ")";
                                                }
                                                return description;
                                            };
                                        };
                                        tmp.pluginStates.put(id, ps);
                                    }
                                }
                            }
                        }
                        if (ps == null && !tmp.pluginStates.containsKey(id)) {
                            final String message = extractionStatus.getExplanation();
                            if (message != null) {
                                ps = new PluginState<PluginProgress>(prog2, null, EXTRACTICONSTART) {
                                    @Override
                                    public PluginProgress getCause() {
                                        final PluginProgress ret = super.getCause();
                                        if (link.getPluginProgress() == ret) {
                                            return ret;
                                        } else {
                                            return null;
                                        }
                                    }

                                    @Override
                                    public String getDescription() {
                                        if (description == null) {
                                            description = message + " (" + link.getName() + ")";
                                        }
                                        return description;
                                    };
                                };
                                tmp.pluginStates.put(id, ps);
                            }
                        }
                        break;
                    }
                }
            }
        }
        if (skipReason != null || finalLinkState != null) {
            tmp.newFinalCount++;
        }
        final boolean isEnabled = link.isEnabled();
        final String displayName;
        if (FORCED_MIRROR_CASE_INSENSITIVE) {
            displayName = view.getDisplayName().toLowerCase(Locale.ENGLISH);
        } else {
            displayName = view.getDisplayName();
        }
        LinkInfo linkInfo = null;
        if (isEnabled) {
            if (finalLinkState == null || FinalLinkState.PLUGIN_DEFECT.equals(finalLinkState)) {
                tmp.allFinished = false;
            }
            tmp.newEnabledCount++;
            if (conditionalSkipReason instanceof MirrorLoading) {
                final MirrorLoading mirrorLoading = (MirrorLoading) conditionalSkipReason;
                final DownloadLink downloadLink = mirrorLoading.getDownloadLink();
                linkInfo = tmp.linkInfos.get(displayName);
                if (linkInfo == null || linkInfo.link != downloadLink) {
                    linkInfo = new LinkInfo();
                    linkInfo.link = downloadLink;
                    final DownloadLinkView downloadView = downloadLink.getView();
                    linkInfo.bytesTotal = downloadView.getBytesTotal();
                    linkInfo.bytesDone = downloadView.getBytesLoaded();
                    final SingleDownloadController controller = downloadLink.getDownloadLinkController();
                    if (controller != null) {
                        final DownloadInterface downloadInterface = controller.getDownloadInstance();
                        if (downloadInterface == null || ((System.currentTimeMillis() - downloadInterface.getStartTimeStamp()) < 5000)) {
                            linkInfo.speed = 0;
                        } else {
                            linkInfo.speed = downloadView.getSpeedBps();
                        }
                    }
                    tmp.linkInfos.put(displayName, linkInfo);
                }
            } else {
                linkInfo = tmp.linkInfos.get(displayName);
                if (linkInfo == null) {
                    linkInfo = new LinkInfo();
                    linkInfo.link = link;
                    tmp.linkInfos.put(displayName, linkInfo);
                }
                linkInfo.enabled = true;
                final SingleDownloadController controller = link.getDownloadLinkController();
                if (controller != null) {
                    linkInfo.bytesTotal = view.getBytesTotal();
                    linkInfo.bytesDone = view.getBytesLoaded();
                    final DownloadInterface downloadInterface = controller.getDownloadInstance();
                    if (downloadInterface == null || ((System.currentTimeMillis() - downloadInterface.getStartTimeStamp()) < 5000)) {
                        linkInfo.speed = 0;
                    } else {
                        linkInfo.speed = view.getSpeedBps();
                    }
                } else {
                    if (linkInfo.speed < 0) {
                        if (linkInfo.bytesTotal < view.getBytesTotal()) {
                            linkInfo.bytesTotal = view.getBytesTotal();
                        }
                        if (linkInfo.bytesDone < view.getBytesLoaded()) {
                            linkInfo.bytesDone = view.getBytesLoaded();
                        }
                    }
                }
            }
        } else {
            linkInfo = tmp.linkInfos.get(displayName);
            if (linkInfo == null) {
                linkInfo = new LinkInfo();
                linkInfo.link = link;
                linkInfo.enabled = false;
                tmp.linkInfos.put(displayName, linkInfo);
            }
            if (linkInfo.enabled == false && linkInfo.speed < 0) {
                if (linkInfo.bytesTotal < view.getBytesTotal()) {
                    linkInfo.bytesTotal = view.getBytesTotal();
                }
                if (linkInfo.bytesDone < view.getBytesLoaded()) {
                    linkInfo.bytesDone = view.getBytesLoaded();
                }
            }
        }
        if (linkInfo != null && FinalLinkState.OFFLINE.equals(finalLinkState) || FinalLinkState.PLUGIN_DEFECT.equals(finalLinkState)) {
            // ignore missing filesize of offline/plugin defect files
            if (linkInfo.bytesTotal < 0) {
                linkInfo.bytesTotal = 0;
            }
            if (linkInfo.bytesDone < 0) {
                linkInfo.bytesDone = 0;
            }
        }
        if (tmp.allFinished && link.getFinishedDate() > tmp.newFinishedDate) {
            /*
             * we can set latest finished date because all links till now are finished
             */
            tmp.newFinishedDate = link.getFinishedDate();
        }
    }

    public int getOfflineCount() {
        return offline;
    }

    public int getOnlineCount() {
        return online;
    }

    @Override
    public void requestUpdate() {
        updatesRequired.incrementAndGet();
    }

    @Override
    public boolean updateRequired() {
        boolean ret = updatesRequired.get() != updatesDone;
        if (ret == false && pkg.isEnabled()) {
            final long now = Time.systemIndependentCurrentJVMTimeMillis();
            if ((now - lastUpdateTimestamp > GUIUPDATETIMEOUT)) {
                lastUpdateTimestamp = now;
                for (final PluginState<?> pluginState : getPluginStates()) {
                    final Object cause = pluginState.getCause();
                    if (cause == null) {
                        ret = true;
                        break;
                    } else if (cause instanceof TimeOutCondition || cause instanceof PluginProgress) {
                        ret = true;
                        break;
                    }
                }
                if (ret == false) {
                    ret = DownloadWatchDog.getInstance().hasRunningDownloads(pkg);
                }
            }
        }
        return ret;
    }

    @Override
    public ChildrenAvailablility getAvailability() {
        return availability;
    }

    @Override
    public String getMessage(Object requestor) {
        if (requestor instanceof AvailabilityColumn) {
            return availabilityColumnString;
        } else {
            return null;
        }
    }

    public String getDownloadDirectory() {
        return pkg.getDownloadDirectory();
    }

    public boolean isRunning() {
        return lastRunningState;
    }

    @Override
    public int size() {
        return count;
    }
}
