package org.jdownloader.extensions.translator;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

import jd.captcha.translate.CaptchaTranslation;
import jd.controlling.reconnect.pluginsinc.batch.translate.BatchTranslation;
import jd.controlling.reconnect.pluginsinc.extern.translate.ExternTranslation;
import jd.controlling.reconnect.pluginsinc.liveheader.translate.LiveheaderTranslation;
import jd.controlling.reconnect.pluginsinc.upnp.translate.UpnpTranslation;
import jd.gui.swing.jdgui.JDGui;

import org.appwork.exceptions.WTFException;
import org.appwork.shutdown.ShutdownController;
import org.appwork.shutdown.ShutdownEvent;
import org.appwork.shutdown.ShutdownRequest;
import org.appwork.storage.JSonStorage;
import org.appwork.swing.exttable.ExtTableTranslation;
import org.appwork.swing.synthetica.LanguageFileSetup;
import org.appwork.swing.synthetica.SyntheticaHelper;
import org.appwork.txtresource.DynamicResourcePath;
import org.appwork.txtresource.TranslateData;
import org.appwork.txtresource.TranslateInterface;
import org.appwork.txtresource.TranslateResource;
import org.appwork.txtresource.TranslatedEntry;
import org.appwork.txtresource.TranslationFactory;
import org.appwork.txtresource.TranslationHandler;
import org.appwork.txtresource.TranslationSource;
import org.appwork.txtresource.TranslationUtils;
import org.appwork.utils.Application;
import org.appwork.utils.Files;
import org.appwork.utils.Hash;
import org.appwork.utils.IO;
import org.appwork.utils.locale.AWUTranslation;
import org.appwork.utils.logging2.sendlogs.LogSenderTranslation;
import org.appwork.utils.swing.EDTHelper;
import org.appwork.utils.swing.dialog.Dialog;
import org.appwork.utils.swing.dialog.DialogCanceledException;
import org.appwork.utils.swing.dialog.DialogClosedException;
import org.appwork.utils.swing.dialog.DialogNoAnswerException;
import org.appwork.utils.swing.dialog.LoginDialog;
import org.appwork.utils.swing.dialog.LoginDialog.LoginData;
import org.jdownloader.api.cnl2.translate.ExternInterfaceTranslation;
import org.jdownloader.controlling.FileCreationManager;
import org.jdownloader.controlling.contextmenu.ContextMenuManager;
import org.jdownloader.controlling.contextmenu.MenuContainerRoot;
import org.jdownloader.controlling.contextmenu.MenuExtenderHandler;
import org.jdownloader.controlling.contextmenu.MenuItemData;
import org.jdownloader.extensions.AbstractExtension;
import org.jdownloader.extensions.ExtensionConfigPanel;
import org.jdownloader.extensions.ExtensionController;
import org.jdownloader.extensions.LazyExtension;
import org.jdownloader.extensions.StartException;
import org.jdownloader.extensions.StopException;
import org.jdownloader.extensions.translator.gui.GuiToggleAction;
import org.jdownloader.extensions.translator.gui.TranslatorGui;
import org.jdownloader.gui.IconKey;
import org.jdownloader.gui.jdtrayicon.translate.TrayiconTranslation;
import org.jdownloader.gui.mainmenu.MenuManagerMainmenu;
import org.jdownloader.gui.mainmenu.container.ExtensionsMenuContainer;
import org.jdownloader.gui.mainmenu.container.ExtensionsMenuWindowContainer;
import org.jdownloader.gui.mainmenu.container.OptionalContainer;
import org.jdownloader.gui.toolbar.MenuManagerMainToolbar;
import org.jdownloader.gui.translate.GuiTranslation;
import org.jdownloader.images.AbstractIcon;
import org.jdownloader.translate.JdownloaderTranslation;
import org.jdownloader.updatev2.UpdaterTranslation;
import org.jdownloader.updatev2.gui.LAFOptions;
import org.tmatesoft.svn.core.SVNDepth;
import org.tmatesoft.svn.core.SVNException;
import org.tmatesoft.svn.core.wc.SVNCommitItem;
import org.tmatesoft.svn.core.wc.SVNCommitPacket;
import org.tmatesoft.svn.core.wc.SVNRevision;

/**
 * Extensionclass. NOTE: All extensions have to follow the namescheme to end with "Extension" and have to extend AbstractExtension
 *
 * @author thomas
 *
 */
public class TranslatorExtension extends AbstractExtension<TranslatorConfig, TranslatorTranslation> implements MenuExtenderHandler {
    /**
     * Extension GUI
     */
    private TranslatorGui                  gui;
    /**
     * List of all available languages
     */
    private java.util.List<TLocale>        translations;
    /**
     * If a translation is loaded, this list contains all it's entries
     */
    private java.util.List<TranslateEntry> translationEntries;
    /**
     * currently loaded Language
     */
    private TLocale                        loaded;
    private TranslatorExtensionEventSender eventSender;
    private Thread                         timer;
    private String                         fontname;

    public String getFontname() {
        return fontname;
    }

    public TranslatorExtension() {
        // Name. The translation Extension itself does not need translation. All
        // translators should be able to read english
        setTitle(T.Translator());
        eventSender = new TranslatorExtensionEventSender();
        if (!Application.isHeadless()) {
            // unload extensions on exit
            ShutdownController.getInstance().addShutdownEvent(new ShutdownEvent() {
                {
                    setHookPriority(Integer.MAX_VALUE);
                }

                @Override
                public void onShutdown(final ShutdownRequest shutdownRequest) {
                    if (!getSettings().isRememberLoginsEnabled()) {
                        try {
                            doLogout();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    } else {
                        try {
                            try {
                                getGUI().stopEditing(true);
                                write();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }
            });
        }
        // init extension GUI
    }

    public TranslatorExtensionEventSender getEventSender() {
        return eventSender;
    }

    /**
     * Action "onStop". Is called each time the user disables the extension
     */
    @Override
    protected void stop() throws StopException {
        MenuManagerMainmenu.getInstance().unregisterExtender(this);
        MenuManagerMainToolbar.getInstance().unregisterExtender(this);
        logger.finer("Stopped " + getClass().getSimpleName());
        if (timer != null) {
            timer.interrupt();
            timer = null;
        }
    }

    @Override
    public boolean isHeadlessRunnable() {
        return false;
    }

    /**
     * Actions "onStart". is called each time the user enables the extension
     */
    @Override
    protected void start() throws StartException {
        if (org.appwork.utils.Application.isHeadless()) {
            throw new StartException("Not available in Headless Mode");
        }
        MenuManagerMainmenu.getInstance().registerExtender(this);
        MenuManagerMainToolbar.getInstance().registerExtender(this);
        // get all LanguageIDs
        List<String> ids = TranslationFactory.listAvailableTranslations(JdownloaderTranslation.class, GuiTranslation.class);
        // create a list of TLocale instances
        translations = new ArrayList<TLocale>();
        for (String id : ids) {
            translations.add(new TLocale(id));
        }
        // sort the list.
        Collections.sort(translations, new Comparator<TLocale>() {
            public int compare(TLocale o1, TLocale o2) {
                return o1.toString().compareTo(o2.toString());
            }
        });
        logger.finer("Started " + getClass().getSimpleName());
        timer = new Thread("TranslatorSyncher") {
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(10 * 60 * 1000);
                        if (getGUI().isShown()) {
                            if (loggedIn) {
                                getGUI().stopEditing(false);
                                refresh();
                            }
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        };
        timer.start();
    }

    @Override
    public MenuItemData updateMenuModel(ContextMenuManager manager, MenuContainerRoot mr) {
        if (manager instanceof MenuManagerMainToolbar) {
            return updateMainToolbar(mr);
        } else if (manager instanceof MenuManagerMainmenu) {
            //
            return updateMainMenu(mr);
        }
        return null;
    }

    private MenuItemData updateMainToolbar(MenuContainerRoot mr) {
        OptionalContainer opt = new OptionalContainer(false);
        opt.add(GuiToggleAction.class);
        return opt;
    }

    private MenuItemData updateMainMenu(MenuContainerRoot mr) {
        ExtensionsMenuContainer container = new ExtensionsMenuContainer();
        ExtensionsMenuWindowContainer windows = new ExtensionsMenuWindowContainer();
        container.add(windows);
        windows.add(GuiToggleAction.class);
        return container;
    }

    protected void refresh() throws InterruptedException {
        if (loaded != null) {
            try {
                write();
                load(loaded, true, true);
            } catch (IOException e) {
                e.printStackTrace();
            } catch (SVNException e) {
                Dialog.getInstance().showExceptionDialog("Error occured", "You got logged out", e);
                doLogout();
            }
        }
    }

    /**
     *
     * @return {@link #translations}
     */
    public java.util.List<TLocale> getTranslations() {
        return translations;
    }

    /**
     * gets called once as soon as the extension is loaded.
     */
    @Override
    protected void initExtension() throws StartException {
    }

    /**
     * Returns the Settingspanel for this extension. If this extension does not have a configpanel, null can be returned
     */
    @Override
    public ExtensionConfigPanel<?> getConfigPanel() {
        return null;
    }

    /**
     * Should return false of this extension has no configpanel
     */
    @Override
    public boolean hasConfigPanel() {
        return false;
    }

    @Override
    public String getDescription() {
        return T.description();
    }

    /**
     * Returns the gui
     */
    @Override
    public TranslatorGui getGUI() {
        if (gui != null) {
            return gui;
        }
        return new EDTHelper<TranslatorGui>() {
            @Override
            public TranslatorGui edtRun() {
                if (gui != null) {
                    return gui;
                }
                gui = new TranslatorGui(TranslatorExtension.this);
                return gui;
            }
        }.getReturnValue();
    }

    /**
     * Loads the given language
     *
     * @param locale
     * @param doRevisionCheck
     *            TODO
     * @param updateSVN
     *            TODO
     * @throws InterruptedException
     * @throws SVNException
     * @throws IOException
     */
    public void load(TLocale locale, boolean doRevisionCheck, boolean updateSVN) throws InterruptedException, SVNException, IOException {
        synchronized (this) {
            if (locale == null) {
                return;
            }
            TLocale oldLoaded = loaded;
            loaded = locale;
            getSettings().setLastLoaded(locale.getId());
            java.util.List<TranslateEntry> tmp = new ArrayList<TranslateEntry>();
            if (updateSVN) {
                Subversion s = new Subversion("svn://svn.jdownloader.org/jdownloader/trunk/translations/translations/", getSettings().getSVNUser(), getSettings().getSVNPassword());
                try {
                    try {
                        long rev = s.getRevisionNoException(Application.getResource("translations/custom"));
                        long newRev = s.update(Application.getResource("translations/custom"), SVNRevision.HEAD, null);
                        if (doRevisionCheck && oldLoaded != null && oldLoaded.equals(loaded) && rev == newRev) {
                            // NO Updates
                            return;
                        }
                    } catch (SVNException e) {
                        try {
                            logger.log(e);
                            s.cleanUp(Application.getResource("translations/custom"), true);
                            long rev = s.getRevisionNoException(Application.getResource("translations/custom"));
                            long newRev = s.update(Application.getResource("translations/custom"), SVNRevision.HEAD, null);
                            if (doRevisionCheck && oldLoaded != null && oldLoaded.equals(loaded) && rev == newRev) {
                                // NO Updates
                                return;
                            }
                        } catch (SVNException e2) {
                            logger.log(e2);
                            Files.deleteRecursiv(Application.getResource("translations/custom"));
                            long rev = s.getRevisionNoException(Application.getResource("translations/custom"));
                            long newRev = s.update(Application.getResource("translations/custom"), SVNRevision.HEAD, null);
                            if (doRevisionCheck && oldLoaded != null && oldLoaded.equals(loaded) && rev == newRev) {
                                // NO Updates
                                return;
                            }
                        }
                    }
                    s.resolveConflicts(Application.getResource("translations/custom"), new ConflictResolveHandler());
                } finally {
                    s.dispose();
                }
            }
            for (LazyExtension le : ExtensionController.getInstance().getExtensions()) {
                try {
                    le.init();
                    if (le._getExtension().getTranslation() == null) {
                        continue;
                    }
                    load(tmp, locale, (Class<? extends TranslateInterface>) le._getExtension().getTranslation().getClass().getInterfaces()[0]);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
            // use Type Hierarchy in IDE to get all interfaces
            // Extension Translations should not be referenced here
            load(tmp, locale, AWUTranslation.class);
            load(tmp, locale, BatchTranslation.class);
            load(tmp, locale, CaptchaTranslation.class);
            load(tmp, locale, ExternInterfaceTranslation.class);
            load(tmp, locale, ExternTranslation.class);
            load(tmp, locale, ExtTableTranslation.class);
            load(tmp, locale, LogSenderTranslation.class);
            load(tmp, locale, GuiTranslation.class);
            LanguageFileSetup guiInterface = TranslationFactory.create(LanguageFileSetup.class);
            fontname = guiInterface.config_fontname();
            //
            if (fontname.equalsIgnoreCase("default")) {
                fontname = new SyntheticaHelper(LAFOptions.getInstance().getCfg()).getDefaultFont();
                if (fontname == null) {
                    fontname = "Tahoma";
                }
            }
            load(tmp, locale, TrayiconTranslation.class);
            load(tmp, locale, JdownloaderTranslation.class);
            load(tmp, locale, LanguageFileSetup.class);
            load(tmp, locale, LiveheaderTranslation.class);
            load(tmp, locale, UpdaterTranslation.class);
            load(tmp, locale, UpnpTranslation.class);
            // there should be no more entries. all of them should have been
            // mapped to an INterface
            this.translationEntries = tmp;
            //
        }
        getEventSender().fireEvent(new TranslatorExtensionEvent(this, org.jdownloader.extensions.translator.TranslatorExtensionEvent.Type.LOADED_TRANSLATION));
    }

    private <T extends TranslateInterface> T load(java.util.List<TranslateEntry> tmp, TLocale locale, Class<T> class1) {
        logger.info("Load Translation " + locale + " " + class1);
        TranslateInterface t = (TranslateInterface) Proxy.newProxyInstance(class1.getClassLoader(), new Class[] { class1 }, new TranslationHandler(class1, locale.getId()));
        for (Method m : t._getHandler().getMethods()) {
            tmp.add(new TranslateEntry(t, m));
        }
        return (T) t;
    }

    /**
     *
     * @return {@link #translationEntries}
     */
    public java.util.List<TranslateEntry> getTranslationEntries() {
        return translationEntries;
    }

    /**
     *
     * @return {@link #loaded}
     */
    public TLocale getLoadedLocale() {
        return loaded;
    }

    private boolean loggedIn  = false;
    private long    lastSave  = System.currentTimeMillis();
    private int     saveCount = 0;

    public boolean isLoggedIn() {
        return loggedIn;
    }

    private void setLoggedIn(boolean loggedIn) {
        synchronized (this) {
            this.loggedIn = loggedIn;
        }
        getEventSender().fireEvent(new TranslatorExtensionEvent(this, TranslatorExtensionEvent.Type.LOGIN_STATUS_CHANGED));
    }

    public void doLogin() throws InterruptedException {
        if (isLoggedIn()) {
            return;
        }
        if (getSettings().isRememberLoginsEnabled()) {
            if (validateSvnLogin(getSettings().getSVNUser(), getSettings().getSVNPassword())) {
                return;
            }
        }
        requestSvnLogin();
    }

    public void doLogout() throws InterruptedException {
        try {
            getGUI().stopEditing(true);
            write();
        } catch (IOException e) {
            e.printStackTrace();
        }
        getSettings().setSVNUser(null);
        getSettings().setSVNPassword(null);
        getSettings().setRememberLoginsEnabled(false);
        loaded = null;
        translationEntries = null;
        setLoggedIn(false);
    }

    public boolean validateSvnLogin(String svnUser, String svnPass) throws InterruptedException {
        setLoggedIn(false);
        logger.info("Login: " + svnUser + " : getSHA256 pass: " + Hash.getSHA256(svnPass));
        if (svnUser.length() >= 3 && svnPass.length() > 3) {
            String url = "svn://svn.jdownloader.org/jdownloader";
            Subversion s = null;
            try {
                s = new Subversion(url, svnUser, svnPass);
                setLoggedIn(true);
                // if (getSettings().getLastLoaded() != null) {
                TLocale pre = getTLocaleByID(getSettings().getLastLoaded());
                if (pre == null) {
                    pre = new TLocale(TranslationFactory.getDesiredLocale().toString());
                }
                load(pre, false, true);
                // }
                return true;
            } catch (SVNException e) {
                logger.log(e);
                Dialog.getInstance().showMessageDialog("SVN Test Error", "Login failed. Username and/or password are not correct!\r\n\r\nServer: " + url);
                doLogout();
            } catch (Throwable e) {
                logger.log(e);
                Dialog.getInstance().showExceptionDialog("Error occured", e.getMessage(), e);
                doLogout();
            } finally {
                try {
                    s.dispose();
                } catch (final Throwable e) {
                }
            }
        } else {
            Dialog.getInstance().showMessageDialog("SVN Test Error", "Username and/or password seem malformed. Test failed.");
        }
        return false;
    }

    @Override
    public String getIconKey() {
        return "language";
    }

    public TLocale getTLocaleByID(String lastLoaded) {
        if (lastLoaded == null) {
            return null;
        }
        for (TLocale t : getTranslations()) {
            if (t.getId().equals(lastLoaded)) {
                return t;
            }
        }
        return null;
    }

    public void requestSvnLogin() throws InterruptedException {
        while (true) {
            final LoginDialog d = new LoginDialog(0, "Translation Server Login", "To modify existing translations, or to create a new one, you need a JDownloader Translator Account.", null);
            d.setUsernameDefault(getSettings().getSVNUser());
            d.setPasswordDefault(getSettings().getSVNPassword());
            d.setRememberDefault(getSettings().isRememberLoginsEnabled());
            LoginData response;
            try {
                response = Dialog.getInstance().showDialog(d);
            } catch (DialogClosedException e) {
                // if (!this.svnLoginOK) validateSvnLogin();
                return;
            } catch (DialogCanceledException e) {
                // if (!this.svnLoginOK) validateSvnLogin();
                return;
            }
            if (validateSvnLogin(response.getUsername(), response.getPassword())) {
                getSettings().setSVNUser(response.getUsername());
                getSettings().setSVNPassword(response.getPassword());
                getSettings().setRememberLoginsEnabled(response.isSave());
                return;
            }
        }
    }

    public void setDefault(java.util.List<TranslateEntry> selection) {
        for (TranslateEntry te : selection) {
            te.setTranslation(te.getDirect());
        }
        getEventSender().fireEvent(new TranslatorExtensionEvent(this, org.jdownloader.extensions.translator.TranslatorExtensionEvent.Type.REFRESH_DATA));
    }

    public void setTranslation(java.util.List<TranslateEntry> selection, TLocale sel) {
        int failed = 0;
        for (TranslateEntry te : selection) {
            Class<? extends TranslateInterface> class1 = (Class<? extends TranslateInterface>) te.getInterface().getClass().getInterfaces()[0];
            TranslateInterface t = (TranslateInterface) Proxy.newProxyInstance(class1.getClassLoader(), new Class[] { class1 }, new TranslationHandler(class1, sel.getId()));
            String translation = t._getHandler().getTranslation(te.getMethod());
            TranslationSource source = t._getHandler().getSource(te.getMethod());
            if (source == null || !source.getID().equals(sel.getId())) {
                failed++;
                continue;
            }
            te.setTranslation(translation);
        }
        if (failed > 0) {
            Dialog.getInstance().showErrorDialog("The " + sel + " Translation is not complete. Not all requested entries could be cloned");
        }
        getEventSender().fireEvent(new TranslatorExtensionEvent(this, org.jdownloader.extensions.translator.TranslatorExtensionEvent.Type.REFRESH_DATA));
    }

    public void reset(TranslateEntry te) {
        te.reset();
    }

    public double getPercent() {
        return (getOK() * 10000 / getTranslationEntries().size()) / 100.0;
    }

    public int getOK() {
        int ok = 0;
        for (TranslateEntry te : getTranslationEntries()) {
            if (te.isMissing() || te.isParameterInvalid()) {
            } else {
                ok++;
            }
        }
        return ok;
    }

    public SVNCommitPacket save() throws IOException, SVNException, InterruptedException {
        if (saveCount > 0 && System.currentTimeMillis() - lastSave < 20 * 60 * 1000) {
            JDGui.help("Upload Changes", "Please do not upload your changes too often.\r\nSave your changes locally first, and upload them when you stop translating or maybe once per hour.", new AbstractIcon(IconKey.ICON_WARNING, 32));
            try {
                Dialog.I().showConfirmDialog(0, "Sure?", "Are you sure that you want to upload the changes?\r\n Your latest commit has been " + ((System.currentTimeMillis() - lastSave) / 1000) + " seconds ago!");
            } catch (DialogNoAnswerException e) {
                return null;
            }
        }
        lastSave = System.currentTimeMillis();
        saveCount++;
        write();
        return upload();
    }

    public boolean hasChanges() {
        synchronized (this) {
            if (loaded == null || getTranslationEntries() == null || getTranslationEntries().size() == 0) {
                return false;
            }
            TLocale localLoaded = loaded;
            HashSet<TranslationHandler> set = new HashSet<TranslationHandler>();
            HashMap<Method, TranslateEntry> map = new HashMap<Method, TranslateEntry>();
            for (TranslateEntry te : getTranslationEntries()) {
                set.add(te.getInterface()._getHandler());
                map.put(te.getMethod(), te);
            }
            for (TranslationHandler h : set) {
                for (Method m : h.getMethods()) {
                    TranslateEntry te = map.get(m);
                    if (te != null && te.isTranslationSet()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public void write() throws IOException, InterruptedException {
        synchronized (this) {
            getGUI().stopEditing(false);
            if (loaded == null || getTranslationEntries() == null || getTranslationEntries().size() == 0) {
                return;
            }
            TLocale localLoaded = loaded;
            TranslationInfo info = JSonStorage.restoreFromFile(Application.getResource("translations/custom/" + localLoaded.getId() + ".json"), new TranslationInfo());
            int more = getTranslationEntries().size() - info.getTotal();
            double p = getPercent();
            if (more > 0) {
                p = ((getOK() + more) * 10000 / getTranslationEntries().size()) / 100.0;
            }
            if (p < info.getComplete()) {
                try {
                    Dialog.getInstance().showConfirmDialog(0, "Are you sure? The Old Version was " + info.getComplete() + "% completed. Your Version has only " + getPercent() + "%. Continue anyway?");
                } catch (DialogNoAnswerException e) {
                    return;
                }
            }
            HashSet<TranslationHandler> set = new HashSet<TranslationHandler>();
            HashMap<Method, TranslateEntry> map = new HashMap<Method, TranslateEntry>();
            for (TranslateEntry te : getTranslationEntries()) {
                set.add(te.getInterface()._getHandler());
                map.put(te.getMethod(), te);
            }
            for (TranslationHandler h : set) {
                TranslateResource res = h.getResource(localLoaded.getId());
                final TranslateData data = new TranslateData();
                for (Method m : h.getMethods()) {
                    TranslateEntry te = map.get(m);
                    if (te.isOK() || te.isDefault()) {
                        data.put(te.getKey(), new TranslatedEntry(te.getTranslation()));
                    }
                }
                String file = TranslationUtils.serialize(data);
                URL url = res.getUrl();
                File newFile = null;
                // if (url != null) {
                // try {
                // newFile = new File(url.toURI());
                // } catch (URISyntaxException e) {
                // newFile = new File(url.getPath());
                // }
                // } else {
                System.out.println("NO URL");
                DynamicResourcePath rPath = h.getInterfaceClass().getAnnotation(DynamicResourcePath.class);
                if (rPath != null) {
                    try {
                        newFile = Application.getResource("translations/custom/" + rPath.value().newInstance().getPath() + "." + localLoaded.getId() + ".lng");
                    } catch (InstantiationException e) {
                        e.printStackTrace();
                    } catch (IllegalAccessException e) {
                        e.printStackTrace();
                    }
                }
                if (newFile == null) {
                    newFile = Application.getResource("translations/custom/" + h.getInterfaceClass().getName().replace(".", "/") + "." + localLoaded.getId() + ".lng");
                }
                // }
                FileCreationManager.getInstance().delete(newFile, null);
                FileCreationManager.getInstance().mkdir(newFile.getParentFile());
                IO.writeStringToFile(newFile, file);
                logger.info("Updated " + file);
            }
            IO.secureWrite(Application.getResource("translations/custom/" + localLoaded.getId() + ".json"), JSonStorage.serializeToJsonByteArray(getInfo()));
        }
        try {
            load(loaded, false, false);
        } catch (SVNException e) {
            e.printStackTrace();
        }
    }

    private TranslationInfo getInfo() {
        TranslationInfo ti = new TranslationInfo();
        ti.setComplete(getPercent());
        ti.setId(loaded.getId());
        ti.setTotal(getTranslationEntries().size());
        return ti;
    }

    public SVNCommitPacket upload() throws SVNException {
        synchronized (this) {
            return new LocaleRunnable<SVNCommitPacket, SVNException>() {
                @Override
                protected SVNCommitPacket run() throws SVNException {
                    Subversion s = new Subversion("svn://svn.jdownloader.org/jdownloader/trunk/translations/translations/", getSettings().getSVNUser(), getSettings().getSVNPassword());
                    try {
                        try {
                            s.update(Application.getResource("translations/custom"), SVNRevision.HEAD, null);
                        } catch (SVNException e) {
                            logger.log(e);
                            s.cleanUp(Application.getResource("translations/custom"), true);
                            s.update(Application.getResource("translations/custom"), SVNRevision.HEAD, null);
                        }
                        s.resolveConflicts(Application.getResource("translations/custom"), new ConflictResolveHandler());
                        s.getWCClient().doAdd(Application.getResource("translations/custom"), true, false, true, SVNDepth.INFINITY, false, false);
                        logger.finer("Create CommitPacket");
                        final SVNCommitPacket packet = s.getCommitClient().doCollectCommitItems(new File[] { Application.getResource("translations/custom") }, false, false, SVNDepth.INFINITY, null);
                        for (SVNCommitItem ci : packet.getCommitItems()) {
                            File file = ci.getFile();
                            if (file.getName().matches(".*\\.r\\d$")) {
                                throw new WTFException("Unresolved Conflicts!");
                            }
                            if (file.isFile() && !file.getName().endsWith("." + getLoadedLocale().getId() + ".lng") && !file.getName().endsWith(getLoadedLocale().getId() + ".json")) {
                                logger.info("Skip: " + file);
                                packet.setCommitItemSkipped(ci, true);
                                continue;
                            }
                            logger.info("Commit: " + file);
                        }
                        logger.finer("Transfer Package");
                        s.getCommitClient().doCommit(packet, true, false, "Updated " + loaded.getLocale().getDisplayName() + " Translation", null);
                        return packet;
                    } finally {
                        s.dispose();
                    }
                }
            }.runEnglish();
        }
    }

    public void revert() throws SVNException, InterruptedException, IOException {
        synchronized (this) {
            Subversion s = new Subversion("svn://svn.jdownloader.org/jdownloader/trunk/translations/translations/", getSettings().getSVNUser(), getSettings().getSVNPassword());
            try {
                try {
                    s.revert(Application.getResource("translations/custom"));
                    s.update(Application.getResource("translations/custom"), SVNRevision.HEAD, null);
                } catch (SVNException e) {
                    logger.log(e);
                    s.cleanUp(Application.getResource("translations/custom"), true);
                    s.update(Application.getResource("translations/custom"), SVNRevision.HEAD, null);
                }
                s.resolveConflicts(Application.getResource("translations/custom"), new ConflictResolveHandler());
            } finally {
                s.dispose();
            }
        }
        load(loaded, false, true);
    }
}
