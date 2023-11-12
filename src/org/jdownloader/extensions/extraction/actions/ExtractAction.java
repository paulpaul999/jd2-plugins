package org.jdownloader.extensions.extraction.actions;

import java.awt.Dialog.ModalityType;
import java.awt.event.ActionEvent;
import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import javax.swing.filechooser.FileFilter;

import org.appwork.storage.config.annotations.LabelInterface;
import org.appwork.swing.components.ExtMergedIcon;
import org.appwork.uio.UIOManager;
import org.appwork.utils.swing.dialog.Dialog;
import org.appwork.utils.swing.dialog.DialogCanceledException;
import org.appwork.utils.swing.dialog.DialogClosedException;
import org.appwork.utils.swing.dialog.ProgressDialog;
import org.appwork.utils.swing.dialog.ProgressDialog.ProgressGetter;
import org.jdownloader.controlling.contextmenu.Customizer;
import org.jdownloader.extensions.extraction.Archive;
import org.jdownloader.extensions.extraction.ExtractionController;
import org.jdownloader.extensions.extraction.ExtractionEvent;
import org.jdownloader.extensions.extraction.ExtractionListener;
import org.jdownloader.extensions.extraction.IExtraction;
import org.jdownloader.extensions.extraction.ValidateArchiveAction;
import org.jdownloader.extensions.extraction.bindings.file.FileArchiveFactory;
import org.jdownloader.extensions.extraction.contextmenu.downloadlist.AbstractExtractionContextAction;
import org.jdownloader.extensions.extraction.multi.ArchiveException;
import org.jdownloader.extensions.extraction.translate.T;
import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.views.DownloadFolderChooserDialog;
import org.jdownloader.images.AbstractIcon;

import jd.gui.UserIO;

public class ExtractAction extends AbstractExtractionContextAction {
    /**
     *
     */
    private static final long serialVersionUID = 1612595219577059496L;

    public static enum ExtractToPathLogic implements LabelInterface {
        ASK_FOR_FOR_EVERY_ARCHIVE {
            @Override
            public String getLabel() {
                return T.T.EXTRACTTOPATHLOGIC_ASK_FOR_EVERY_ARCHIVE();
            }
        },
        EXTRACT_TO_ARCHIVE_PARENT {
            @Override
            public String getLabel() {
                return T.T.EXTRACTTOPATHLOGIC_EXTRACT_TO_ARCHIVE_PARENT();
            }
        },
        ASK_ONCE {
            @Override
            public String getLabel() {
                return T.T.EXTRACTTOPATHLOGIC_ASK_ONCE();
            }
        },
        USE_CUSTOMEXTRACTIONPATH {
            @Override
            public String getLabel() {
                return T.T.EXTRACTTOPATHLOGIC_USE_CUSTOMEXTRACTIONPATH();
            }
        };
    }

    private ExtractToPathLogic extractToPathLogic = ExtractToPathLogic.EXTRACT_TO_ARCHIVE_PARENT;

    public static String getTranslationForExtractToPathLogic() {
        return T.T.ExtractAction_getTranslationForExtractToPathLogic();
    }

    @Customizer(link = "#getTranslationForExtractToPathLogic")
    public ExtractToPathLogic getExtractToPathLogic() {
        return extractToPathLogic;
    }

    @Override
    protected void requestUpdateSelection() {
    }

    public void setExtractToPathLogic(ExtractToPathLogic extractToPathLogic) {
        this.extractToPathLogic = extractToPathLogic;
    }

    public ExtractAction() {
        super();
        setName(T.T.menu_tools_extract_files());
        setIconKey(org.jdownloader.gui.IconKey.ICON_EXTRACT);
    }

    public boolean isEnabled() {
        return true;
    }

    public void actionPerformed(ActionEvent e) {
        new Thread("Extracting") {
            public void run() {
                final FileFilter fileFilter = new FileFilter() {
                    @Override
                    public boolean accept(final File pathname) {
                        if (pathname.isDirectory()) {
                            return true;
                        } else {
                            final FileArchiveFactory factory = new FileArchiveFactory(pathname);
                            for (IExtraction extractor : _getExtension().getExtractors()) {
                                /* no deep inspection to speedup the accept method */
                                final boolean deepInspection = false;
                                if (!Boolean.FALSE.equals(extractor.isSupported(factory, deepInspection))) {
                                    try {
                                        final Archive archive = extractor.buildArchive(factory, deepInspection);
                                        if (archive != null && factory.getName().equals(archive.getArchiveFiles().get(0).getName())) {
                                            return true;
                                        } else {
                                            // some extensions, eg zip, are supported by multiple IExtraction
                                            // return false;
                                        }
                                    } catch (ArchiveException e) {
                                        _getExtension().getLogger().log(e);
                                    }
                                }
                            }
                            return false;
                        }
                    }

                    @Override
                    public String getDescription() {
                        return org.jdownloader.extensions.extraction.translate.T.T.plugins_optional_extraction_filefilter();
                    }
                };
                final File[] dialogFiles = UserIO.getInstance().requestFileChooser("_EXTRATION_", null, UserIO.FILES_AND_DIRECTORIES, fileFilter, true, null, null);
                final List<File> files = new ArrayList<File>();
                if (dialogFiles != null) {
                    for (final File dialogFile : dialogFiles) {
                        if (dialogFile.isFile()) {
                            files.add(dialogFile);
                        } else if (dialogFile.isDirectory()) {
                            dialogFile.listFiles(new java.io.FileFilter() {
                                @Override
                                public boolean accept(final File pathname) {
                                    if (pathname.isDirectory()) {
                                        return false;
                                    } else if (fileFilter.accept(pathname)) {
                                        files.add(pathname);
                                        return true;
                                    } else {
                                        return false;
                                    }
                                }
                            });
                        }
                    }
                }
                if (files.size() == 0) {
                    return;
                }
                try {
                    File extractTo = null;
                    if (getExtractToPathLogic() == ExtractToPathLogic.ASK_ONCE) {
                        extractTo = DownloadFolderChooserDialog.open(null, false, "Extract To");
                    }
                    for (final File archiveStartFile : files) {
                        try {
                            final Archive archive = _getExtension().buildArchive(new FileArchiveFactory(archiveStartFile));
                            if (archive == null) {
                                /* archive can be null because deep inspection may result in insupported archive */
                                continue;
                            }
                            switch (getExtractToPathLogic()) {
                            case USE_CUSTOMEXTRACTIONPATH:
                                archive.getSettings().setExtractPath(_getExtension().getSettings().getCustomExtractionPath());
                                break;
                            case ASK_FOR_FOR_EVERY_ARCHIVE:
                                if (_getExtension().getSettings().isCustomExtractionPathEnabled()) {
                                    final File path = DownloadFolderChooserDialog.open(new File(_getExtension().getSettings().getCustomExtractionPath()), false, "Extract To");
                                    archive.getSettings().setExtractPath(path.getAbsolutePath());
                                } else {
                                    final File path = DownloadFolderChooserDialog.open(archiveStartFile.getParentFile(), false, "Extract To");
                                    archive.getSettings().setExtractPath(path.getAbsolutePath());
                                }
                                break;
                            case ASK_ONCE:
                                archive.getSettings().setExtractPath(extractTo.getAbsolutePath());
                                break;
                            case EXTRACT_TO_ARCHIVE_PARENT:
                                archive.getSettings().setExtractPath(archiveStartFile.getParentFile().getAbsolutePath());
                                break;
                            }
                            ProgressDialog dialog = new ProgressDialog(new ProgressGetter() {
                                private volatile ExtractionController controller = null;

                                @Override
                                public void run() throws Exception {
                                    if (_getExtension().isComplete(archive)) {
                                        controller = _getExtension().addToQueue(archive, true);
                                        if (controller != null) {
                                            final ExtractionListener listener = new ExtractionListener() {
                                                @Override
                                                public void onExtractionEvent(ExtractionEvent event) {
                                                    if (event.getCaller() == controller) {
                                                        switch (event.getType()) {
                                                        case CLEANUP:
                                                            _getExtension().getEventSender().removeListener(this);
                                                            break;
                                                        case EXTRACTION_FAILED:
                                                        case EXTRACTION_FAILED_CRC:
                                                            if (controller.getException() != null) {
                                                                Dialog.getInstance().showExceptionDialog(org.jdownloader.extensions.extraction.translate.T.T.extraction_failed(archiveStartFile.getName()), controller.getException().getLocalizedMessage(), controller.getException());
                                                            } else {
                                                                Dialog.getInstance().showErrorDialog(org.jdownloader.extensions.extraction.translate.T.T.extraction_failed(archiveStartFile.getName()));
                                                            }
                                                            break;
                                                        }
                                                    }
                                                }
                                            };
                                            try {
                                                _getExtension().getEventSender().addListener(listener, true);
                                                while (!controller.isFinished()) {
                                                    Thread.sleep(1000);
                                                }
                                            } catch (InterruptedException e) {
                                                controller.kill();
                                                throw e;
                                            }
                                        }
                                    } else {
                                        new ValidateArchiveAction(_getExtension(), archive).actionPerformed(null);
                                    }
                                }

                                private final DecimalFormat format = new DecimalFormat("00.00");

                                @Override
                                public String getString() {
                                    final ExtractionController lController = controller;
                                    if (lController != null) {
                                        return T.T.extractprogress_label(format.format(lController.getProgress()) + " %", lController.getArchive().getExtractedFiles().size() + "");
                                    } else {
                                        return format.format(0d) + " %";
                                    }
                                }

                                @Override
                                public int getProgress() {
                                    final ExtractionController lController = controller;
                                    if (lController == null) {
                                        return 0;
                                    }
                                    return Math.min(99, (int) lController.getProgress());
                                }

                                @Override
                                public String getLabelString() {
                                    return null;
                                }
                            }, 0, T.T.extracting_archive(archive.getName()), T.T.extracting_wait(archive.getName()), new ExtMergedIcon(new AbstractIcon(org.jdownloader.gui.IconKey.ICON_EXTRACT, 32)).add(new AbstractIcon(IconKey.ICON_MEDIA_PLAYBACK_START, 24), 6, 6), null, null) {
                                @Override
                                public ModalityType getModalityType() {
                                    return ModalityType.MODELESS;
                                }
                            };
                            // UIOManager.I().show(class1, impl)
                            UIOManager.I().show(null, dialog);
                            dialog.throwCloseExceptions();
                        } catch (ArchiveException e1) {
                            _getExtension().getLogger().log(e1);
                        }
                    }
                } catch (DialogClosedException e) {
                    e.printStackTrace();
                } catch (DialogCanceledException e) {
                    e.printStackTrace();
                }
            }
        }.start();
    }
}
