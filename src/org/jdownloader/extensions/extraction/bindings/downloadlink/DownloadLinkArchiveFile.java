package org.jdownloader.extensions.extraction.bindings.downloadlink;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import jd.controlling.downloadcontroller.DownloadController;
import jd.controlling.downloadcontroller.DownloadWatchDog;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.DownloadLinkProperty;
import jd.plugins.FilePackage;
import jd.plugins.FilePackageView;
import jd.plugins.LinkInfo;
import jd.plugins.PluginProgress;

import org.appwork.utils.StringUtils;
import org.appwork.utils.event.queue.QueueAction;
import org.jdownloader.controlling.FileCreationManager.DeleteOption;
import org.jdownloader.controlling.FileStateManager;
import org.jdownloader.controlling.FileStateManager.FILESTATE;
import org.jdownloader.extensions.extraction.Archive;
import org.jdownloader.extensions.extraction.ArchiveFile;
import org.jdownloader.extensions.extraction.ExtractionController;
import org.jdownloader.extensions.extraction.ExtractionProgress;
import org.jdownloader.extensions.extraction.ExtractionStatus;
import org.jdownloader.plugins.FinalLinkState;
import org.jdownloader.plugins.SkipReason;
import org.jdownloader.settings.CleanAfterDownloadAction;
import org.jdownloader.settings.staticreferences.CFG_GENERAL;

public class DownloadLinkArchiveFile implements ArchiveFile {
    private final List<DownloadLink>       downloadLinks;
    private final String                   name;
    private final String                   filePath;
    private volatile long                  size;
    private final int                      hashCode;
    private final AtomicReference<Boolean> exists                = new AtomicReference<Boolean>(null);
    private boolean                        fileArchiveFileExists = false;

    public boolean isFileArchiveFileExists() {
        return fileArchiveFileExists;
    }

    public void setFileArchiveFileExists(boolean fileArchiveFileExists) {
        if (fileArchiveFileExists) {
            this.fileArchiveFileExists = true;
            setExists(true);
        } else {
            invalidateExists();
        }
    }

    @Override
    public LinkInfo getLinkInfo() {
        return getDownloadLinks().get(0).getLinkInfo();
    }

    public DownloadLinkArchiveFile(DownloadLink link) {
        downloadLinks = new CopyOnWriteArrayList<DownloadLink>();
        downloadLinks.add(link);
        filePath = link.getFileOutput(false, true);
        name = new File(getFilePath()).getName();
        size = link.getView().getBytesTotalEstimated();
        hashCode = (getClass() + name).hashCode();
    }

    public String toString() {
        return "DownloadLink:" + getFilePath() + "|Complete:" + isComplete();
    }

    @Override
    public int hashCode() {
        return hashCode;
    }

    @Override
    public boolean contains(Object obj) {
        if (obj == this) {
            return true;
        } else if (obj != null) {
            if (obj instanceof String) {
                return getFilePath().equals(obj);
            } else if (obj instanceof ArchiveFile && StringUtils.equals(getFilePath(), ((ArchiveFile) obj).getFilePath())) {
                return true;
            } else if (obj instanceof DownloadLinkArchiveFile) {
                for (final DownloadLink dl : ((DownloadLinkArchiveFile) obj).getDownloadLinks()) {
                    if (getDownloadLinks().contains(dl)) {
                        return true;
                    }
                }
            } else if (obj instanceof DownloadLink) {
                if (getDownloadLinks().contains(obj)) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public Boolean isComplete() {
        if (isFileArchiveFileExists() && exists()) {
            return Boolean.TRUE;
        }
        for (DownloadLink downloadLink : getDownloadLinks()) {
            if ((SkipReason.FILE_EXISTS.equals(downloadLink.getSkipReason()) || FinalLinkState.FAILED_EXISTS.equals(downloadLink.getFinalLinkState()) || FinalLinkState.CheckFinished(downloadLink.getFinalLinkState()))) {
                return Boolean.TRUE;
            }
        }
        return Boolean.FALSE;
    }

    public String getFilePath() {
        return filePath;
    }

    public void deleteFile(DeleteOption option) {
        DownloadWatchDog.getInstance().delete(new ArrayList<DownloadLink>(getDownloadLinks()), option, true);
        setFileArchiveFileExists(false);
    }

    public List<DownloadLink> getDownloadLinks() {
        return downloadLinks;
    }

    public String getName() {
        return name;
    }

    public void setStatus(ExtractionController controller, ExtractionStatus status) {
        for (final DownloadLink downloadLink : getDownloadLinks()) {
            if (!FilePackage.isDefaultFilePackage(downloadLink.getFilePackage())) {
                downloadLink.setExtractionStatus(status);
                if (status != null) {
                    final PluginProgress progress = downloadLink.getPluginProgress();
                    if (progress != null && progress instanceof ExtractionProgress) {
                        ((ExtractionProgress) progress).setMessage(status.getExplanation());
                    }
                }
            }
        }
    }

    public void setMessage(ExtractionController controller, String text) {
        for (final DownloadLink downloadLink : getDownloadLinks()) {
            if (!FilePackage.isDefaultFilePackage(downloadLink.getFilePackage())) {
                final PluginProgress progress = downloadLink.getPluginProgress();
                if (progress != null && progress instanceof ExtractionProgress) {
                    ((ExtractionProgress) progress).setMessage(text);
                }
            }
        }
    }

    public void setProgress(ExtractionController controller, long value, long max, Color color) {
        final PluginProgress progress = controller.getExtractionProgress();
        progress.updateValues(value, max);
        progress.setColor(color);
        for (final DownloadLink downloadLink : getDownloadLinks()) {
            if (!FilePackage.isDefaultFilePackage(downloadLink.getFilePackage())) {
                if (value <= 0 && max <= 0) {
                    /* moves to top */
                    downloadLink.addPluginProgress(progress);
                } else {
                    if (!downloadLink.hasPluginProgress(progress)) {
                        downloadLink.addPluginProgress(progress);
                    }
                    if (downloadLink.getPluginProgress() == progress) {
                        final FilePackageView view = downloadLink.getParentNode().getView();
                        if (view != null) {
                            view.requestUpdate();
                        }
                    }
                }
            }
        }
    }

    @Override
    public long getFileSize() {
        if (exists()) {
            return Math.max(new File(getFilePath()).length(), size);
        } else {
            return Math.max(0, size);
        }
    }

    public void addMirror(DownloadLink link) {
        getDownloadLinks().add(link);
        size = Math.max(link.getView().getBytesTotal(), size);
    }

    public AvailableStatus getAvailableStatus() {
        AvailableStatus ret = null;
        for (final DownloadLink downloadLink : getDownloadLinks()) {
            switch (downloadLink.getAvailableStatus()) {
            case TRUE:
                return downloadLink.getAvailableStatus();
            case UNCHECKED:
                ret = AvailableStatus.UNCHECKED;
                break;
            case UNCHECKABLE:
                if (ret != AvailableStatus.UNCHECKED) {
                    ret = AvailableStatus.UNCHECKABLE;
                }
                break;
            case FALSE:
                if (ret == null) {
                    ret = AvailableStatus.FALSE;
                }
                break;
            }
        }
        return ret;
    }

    @Override
    public void onCleanedUp(final ExtractionController controller) {
        if (controller.isSuccessful()) {
            final List<ExtractionController> controllers = controller.getExtension().getExtractionControllers(controller.getArchive().getFactory());
            if (controllers.size() > 1 || (controllers.size() == 1 && controllers.get(0) != controller)) {
                controller.getLogger().info("skip cleanup of " + getName() + " because there are other controllers sharing same archive files!");
                return;
            }
            final CleanAfterDownloadAction cleanupCFG = CFG_GENERAL.CFG.getCleanupAfterDownloadAction();
            final CleanAfterDownloadAction cleanup;
            if (controller.getExtension().isRemoveDownloadLinksAfterExtractEnabled(controller.getArchive())) {
                cleanup = CleanAfterDownloadAction.CLEANUP_IMMEDIATELY;
            } else {
                cleanup = cleanupCFG;
            }
            switch (cleanup) {
            case CLEANUP_IMMEDIATELY:
                DownloadController.getInstance().getQueue().add(new QueueAction<Void, RuntimeException>() {
                    @Override
                    protected Void run() throws RuntimeException {
                        final List<DownloadLink> ask = new ArrayList<DownloadLink>();
                        for (final DownloadLink downloadLink : getDownloadLinks()) {
                            if (DownloadController.getInstance() == downloadLink.getFilePackage().getControlledBy()) {
                                ask.add(downloadLink);
                            }
                        }
                        if (ask.size() > 0) {
                            final List<DownloadLink> response = DownloadController.getInstance().askForRemoveVetos(controller, ask);
                            if (response.size() > 0) {
                                for (final DownloadLink downloadLink : response) {
                                    controller.getLogger().info(cleanup + ":" + cleanupCFG + ":" + downloadLink.getView().getDisplayName() + "|" + downloadLink.getHost());
                                }
                                DownloadController.getInstance().removeChildren(response);
                                invalidateExists();
                            }
                            ask.removeAll(response);
                            for (final DownloadLink downloadLink : ask) {
                                controller.getLogger().info(cleanup + ":" + cleanupCFG + ":" + downloadLink.getView().getDisplayName() + "|" + downloadLink.getHost() + " failed because of removeVetos!");
                            }
                        }
                        return null;
                    }
                });
                break;
            case CLEANUP_AFTER_PACKAGE_HAS_FINISHED:
                DownloadController.getInstance().getQueue().add(new QueueAction<Void, RuntimeException>() {
                    @Override
                    protected Void run() throws RuntimeException {
                        final HashSet<FilePackage> fps = new HashSet<FilePackage>();
                        for (final DownloadLink downloadLink : getDownloadLinks()) {
                            if (DownloadController.getInstance() == downloadLink.getFilePackage().getControlledBy()) {
                                fps.add(downloadLink.getFilePackage());
                            }
                        }
                        for (final FilePackage fp : fps) {
                            DownloadController.removePackageIfFinished(controller, controller.getLogger(), fp);
                        }
                        invalidateExists();
                        return null;
                    }
                });
                break;
            case CLEANUP_ONCE_AT_STARTUP:
            case NEVER:
                controller.getLogger().info(cleanupCFG + ":" + getName());
            }
        }
    }

    @Override
    public void setArchive(final Archive archive) {
        if (archive != null) {
            final String archiveID = archive.getArchiveID();
            boolean mergeSourcePasswords = false;
            for (final DownloadLink downloadLink : getDownloadLinks()) {
                downloadLink.setArchiveID(archiveID);
                if (downloadLink.getSourcePluginPasswordList() != null) {
                    mergeSourcePasswords = true;
                }
            }
            if (mergeSourcePasswords) {
                final List<String> existingPasswords = archive.getSettings().getPasswords();
                final List<String> newPasswords = new ArrayList<String>();
                final String finalPassword = archive.getSettings().getFinalPassword();
                if (StringUtils.isNotEmpty(finalPassword) && (existingPasswords == null || !existingPasswords.contains(finalPassword))) {
                    newPasswords.add(finalPassword);
                }
                for (final DownloadLink downloadLink : getDownloadLinks()) {
                    final List<String> sourcePluginPasswords = downloadLink.getSourcePluginPasswordList();
                    if (sourcePluginPasswords != null) {
                        for (final String sourcePluginPassword : sourcePluginPasswords) {
                            if (StringUtils.isNotEmpty(sourcePluginPassword) && (existingPasswords == null || !existingPasswords.contains(sourcePluginPassword))) {
                                newPasswords.add(sourcePluginPassword);
                            }
                        }
                    }
                }
                if (newPasswords.size() > 0) {
                    if (existingPasswords != null) {
                        newPasswords.addAll(existingPasswords);
                    }
                    archive.getSettings().setPasswords(newPasswords);
                }
            }
        }
    }

    @Override
    public String getArchiveID() {
        final List<DownloadLink> links = getDownloadLinks();
        if (links.size() == 0) {
            return null;
        } else if (links.size() == 1) {
            return links.get(0).getArchiveID();
        } else {
            final HashMap<String, ArchiveID> scores = new HashMap<String, ArchiveID>();
            for (final DownloadLink downloadLink : getDownloadLinks()) {
                final String archiveID = downloadLink.getArchiveID();
                if (archiveID != null) {
                    ArchiveID score = scores.get(archiveID);
                    if (score == null) {
                        score = new ArchiveID(archiveID);
                        scores.put(archiveID, score);
                    }
                    score.increaseScore();
                }
            }
            if (scores.size() == 0) {
                return null;
            } else if (scores.size() == 1) {
                return scores.values().iterator().next().getArchiveID();
            } else {
                ArchiveID ret = null;
                for (final ArchiveID score : scores.values()) {
                    if (ret == null || ret.getScore() < score.getScore()) {
                        ret = score;
                    }
                }
                return ret.getArchiveID();
            }
        }
    }

    @Override
    public boolean exists() {
        return exists(false);
    };

    @Override
    public boolean exists(boolean ignoreCache) {
        final File file = new File(getFilePath());
        if (FileStateManager.getInstance().hasFileState(file, FILESTATE.WRITE_EXCLUSIVE)) {
            return false;
        }
        Boolean ret = ignoreCache ? null : exists.get();
        if (ret == null) {
            ret = file.isFile();
            exists.compareAndSet(null, ret);
        }
        return ret;
    }

    protected void setExists(boolean b) {
        exists.set(b);
    }

    @Override
    public void notifyChanges(Object type) {
        for (DownloadLink link : getDownloadLinks()) {
            link.firePropertyChanged(DownloadLinkProperty.Property.ARCHIVE, type);
        }
    }

    @Override
    public void removePluginProgress(ExtractionController controller) {
        for (final DownloadLink downloadLink : getDownloadLinks()) {
            downloadLink.removePluginProgress(controller.getExtractionProgress());
        }
    }

    @Override
    public void invalidateExists() {
        this.fileArchiveFileExists = false;
        exists.set(null);
    }

    @Override
    public void setPartOfAnArchive(Boolean b) {
        for (DownloadLink link : getDownloadLinks()) {
            link.setPartOfAnArchive(b);
        }
    }

    @Override
    public Boolean isPartOfAnArchive() {
        Boolean ret = null;
        for (DownloadLink link : getDownloadLinks()) {
            final Boolean newRet = link.isPartOfAnArchive();
            if (newRet != null) {
                if (Boolean.TRUE.equals(newRet)) {
                    return newRet;
                }
                if (ret == null) {
                    ret = newRet;
                }
            }
        }
        return ret;
    }
}
