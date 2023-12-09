package jd.plugins.hoster;

import java.util.Arrays;

import javax.swing.JLabel;
import javax.swing.SpinnerNumberModel;

import org.appwork.swing.components.ExtCheckBox;
import org.appwork.swing.components.ExtSpinner;
import org.appwork.swing.components.ExtTextField;
import org.appwork.utils.StringUtils;
import org.appwork.utils.net.usenet.InvalidAuthException;
import org.jdownloader.gui.InputChangedCallbackInterface;
import org.jdownloader.gui.translate._GUI;
import org.jdownloader.plugins.accounts.AccountBuilderInterface;
import org.jdownloader.plugins.components.usenet.UsenetAccountConfigInterface;
import org.jdownloader.plugins.components.usenet.UsenetServer;
import org.jdownloader.plugins.controller.LazyPlugin;
import org.jdownloader.plugins.controller.host.HostPluginController;
import org.jdownloader.plugins.controller.host.LazyHostPlugin;

import jd.PluginWrapper;
import jd.http.Browser;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.AccountInvalidException;
import jd.plugins.DefaultEditAccountPanel;
import jd.plugins.DownloadLink;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginConfigPanelNG;
import jd.plugins.PluginException;

@HostPlugin(revision = "$Revision: 48560 $", interfaceVersion = 2, names = { "genericusenet" }, urls = { "" })
public class GenericUseNet extends UseNet {
    public GenericUseNet(PluginWrapper wrapper) {
        super(wrapper);
        enablePremium();
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.GENERIC, LazyPlugin.FEATURE.USENET };
    }

    public static interface GenericUsenetAccountConfig extends UsenetAccountConfigInterface {
    }

    @Override
    public GenericUsenetAccountConfig getAccountJsonConfig(Account acc) {
        return (GenericUsenetAccountConfig) super.getAccountJsonConfig(acc);
    }

    @Override
    protected PluginConfigPanelNG createConfigPanel() {
        return new PluginConfigPanelNG() {
            @Override
            public void updateContents() {
            }

            @Override
            public void save() {
            }
        };
    }

    @Override
    public void extendAccountSettingsPanel(Account acc, PluginConfigPanelNG panel) {
    }

    @Override
    public void handleFree(DownloadLink link) throws Exception {
        if ("genericusenet".equals(link.getHost())) {
            final LazyHostPlugin usenetPlugin = HostPluginController.getInstance().get("usenet");
            usenetPlugin.getPrototype(null).assignPlugin(null, link);
            throw new PluginException(LinkStatus.ERROR_RETRY);
        } else {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
    }

    @Override
    public void handlePremium(DownloadLink link, Account account) throws Exception {
        if ("genericusenet".equals(link.getHost())) {
            final LazyHostPlugin usenetPlugin = HostPluginController.getInstance().get("usenet");
            usenetPlugin.getPrototype(null).assignPlugin(null, link);
            throw new PluginException(LinkStatus.ERROR_RETRY);
        } else {
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
    }

    @Override
    public AccountInfo fetchAccountInfo(Account account) throws Exception {
        try {
            verifyUseNetLogins(convertNNTPLoginURI(account));
            final AccountInfo ai = new AccountInfo();
            ai.setProperty("multiHostSupport", Arrays.asList(new String[] { "usenet" }));
            ai.setStatus("Generic usenet:maxDownloads(current)=" + account.getMaxSimultanDownloads());
            account.setRefreshTimeout(2 * 60 * 60 * 1000l);
            return ai;
        } catch (InvalidAuthException e) {
            throw new PluginException(LinkStatus.ERROR_PREMIUM, null, PluginException.VALUE_ID_PREMIUM_DISABLE, e);
        }
    }

    @Override
    public AccountBuilderInterface getAccountFactory(final InputChangedCallbackInterface callback) {
        return new DefaultEditAccountPanel(callback) {
            private final ExtTextField host;
            private final ExtCheckBox  ssl;
            private final ExtSpinner   port;
            private final ExtSpinner   connections;
            {
                add(new JLabel(_GUI.T.UsenetConfigPanel_Server()));
                add(host = new ExtTextField() {
                    @Override
                    public void onChanged() {
                        callback.onChangedInput(host);
                    }
                });
                host.setHelpText(_GUI.T.jd_gui_userio_defaulttitle_input());
                add(new JLabel(_GUI.T.UsenetConfigPanel_ssl()));
                add(ssl = new ExtCheckBox());
                add(new JLabel(_GUI.T.UsenetConfigPanel_port()));
                add(port = new ExtSpinner(new SpinnerNumberModel(119, 80, 65535, 1)));
                add(new JLabel(_GUI.T.PackagizerFilterRuleDialog_layoutDialogContent_chunks()));
                add(connections = new ExtSpinner(new SpinnerNumberModel(1, 1, 100, 1)));
            }

            @Override
            public void setAccount(Account defaultAccount) {
                super.setAccount(defaultAccount);
                if (defaultAccount != null) {
                    GenericUsenetAccountConfig cfg = getAccountJsonConfig(defaultAccount);
                    ssl.setSelected(cfg.isSSLEnabled());
                    host.setText(cfg.getHost());
                    int p = cfg.getPort();
                    if (p <= 0) {
                        p = ssl.isSelected() ? 563 : 119;
                    }
                    port.setValue(p);
                    connections.setValue(Math.max(1, defaultAccount.getMaxSimultanDownloads()));
                }
            }

            @Override
            public boolean validateInputs() {
                final String host = this.host.getText();
                return StringUtils.isNotEmpty(host);
            }

            @Override
            public boolean updateAccount(Account input, Account output) {
                super.updateAccount(input, output);
                GenericUsenetAccountConfig outCfg = getAccountJsonConfig(output);
                GenericUsenetAccountConfig inCfg = getAccountJsonConfig(input);
                outCfg.setSSLEnabled(inCfg.isSSLEnabled());
                outCfg.setHost(inCfg.getHost());
                int p = inCfg.getPort();
                if (p <= 0) {
                    p = ssl.isSelected() ? 563 : 119;
                }
                outCfg.setPort(p);
                output.setMaxSimultanDownloads(input.getMaxSimultanDownloads());
                return true;
            }

            @Override
            public Account getAccount() {
                final Account acc = super.getAccount();
                GenericUsenetAccountConfig cfg = getAccountJsonConfig(acc);
                cfg.setSSLEnabled(ssl.isSelected());
                cfg.setHost(host.getText().trim());
                cfg.setPort(((Number) port.getValue()).intValue());
                acc.setMaxSimultanDownloads(Math.max(1, (Integer) connections.getValue()));
                return acc;
            }
        };
    }

    @Override
    protected UsenetServer getUseNetServer(Account account) throws Exception {
        final GenericUsenetAccountConfig cfg = getAccountJsonConfig(account);
        final boolean ssl = cfg.isSSLEnabled();
        final int port = cfg.getPort();
        final String host = cfg.getHost();
        if (host == null) {
            throw new AccountInvalidException("No usenet host set!");
        } else {
            final UsenetServer server = new UsenetServer(host, port, ssl);
            return server;
        }
    }

    @Override
    public String getHost(final DownloadLink link, Account account, boolean includeSubdomain) {
        if (account != null) {
            final GenericUsenetAccountConfig cfg = getAccountJsonConfig(account);
            final String host = cfg.getHost();
            if (host != null) {
                return Browser.getHost(host, includeSubdomain);
            }
        }
        return super.getHost(link, account, includeSubdomain);
    }
}
