//    jDownloader - Downloadmanager
//    Copyright (C) 2013  JD-Team support@jdownloader.org
//
//    This program is free software: you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation, either version 3 of the License, or
//    (at your option) any later version.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//    GNU General Public License for more details.
//
//    You should have received a copy of the GNU General Public License
//    along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JComponent;
import javax.swing.JLabel;

import org.appwork.storage.config.annotations.AboutConfig;
import org.appwork.storage.config.annotations.DefaultBooleanValue;
import org.appwork.storage.config.handler.KeyHandler;
import org.appwork.swing.MigPanel;
import org.appwork.swing.components.ExtPasswordField;
import org.appwork.utils.StringUtils;
import org.jdownloader.gui.InputChangedCallbackInterface;
import org.jdownloader.plugins.accounts.AccountBuilderInterface;
import org.jdownloader.plugins.components.usenet.UsenetAccountConfigInterface;
import org.jdownloader.plugins.components.usenet.UsenetConfigPanel;
import org.jdownloader.plugins.components.usenet.UsenetServer;
import org.jdownloader.plugins.config.AccountConfigInterface;
import org.jdownloader.plugins.config.Order;
import org.jdownloader.plugins.controller.LazyPlugin;

import jd.PluginWrapper;
import jd.gui.swing.components.linkbutton.JLink;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.HostPlugin;
import jd.plugins.PluginConfigPanelNG;
import jd.plugins.PluginForHost;
import jd.plugins.components.MultiHosterManagement;

@HostPlugin(revision = "$Revision: 48430 $", interfaceVersion = 3, names = { "premiumize.me" }, urls = { "https?://(?:[a-z0-9\\.\\-]+)?premiumize\\.me/file\\?id=([A-Za-z0-9\\-_]+)" })
public class PremiumizeMe extends ZeveraCore {
    protected static MultiHosterManagement mhm = new MultiHosterManagement("premiumize.me");

    public PremiumizeMe(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("https://www." + this.getHost() + "/premium");
    }

    @Override
    public String getClientID() {
        return getClientIDExt();
    }

    public static String getClientIDExt() {
        return "616325511";
    }

    @Override
    public int getDownloadModeMaxChunks(final Account account) {
        if (account != null && account.getType() == AccountType.FREE) {
            /* Free Account */
            return 0;
        } else if (account != null && account.getType() == AccountType.PREMIUM) {
            /* Premium account */
            return 0;
        } else {
            /* Free(anonymous) and unknown account type */
            return 0;
        }
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return -1;
    }

    @Override
    public int getMaxSimultaneousFreeAccountDownloads() {
        /* 2019-02-19: premiumize.me/free */
        return 1;
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    public static interface PremiumizeMeConfigInterface extends UsenetAccountConfigInterface {
        public class Translation {
            public String getEnablePairingLogin_label() {
                return "Enable pairing login?\r\nOnce enabled, you won't be able to use Usenet with Premiumize in JD anymore!!";
            }

            public String getEnableBoosterPointsUnlimitedTrafficWorkaround_label() {
                return "Enable booster points unlimited traffic workaround for this account? \r\nThis is only for owners of booster-points! \r\nMore information: premiumize.me/booster";
            }
        }

        public static final PremiumizeMeConfigInterface.Translation TRANSLATION = new Translation();

        // @AboutConfig
        @DefaultBooleanValue(false)
        @Order(20)
        boolean isEnablePairingLogin();

        void setEnablePairingLogin(boolean b);

        @AboutConfig
        @DefaultBooleanValue(false)
        @Order(30)
        boolean isEnableBoosterPointsUnlimitedTrafficWorkaround();

        void setEnableBoosterPointsUnlimitedTrafficWorkaround(boolean b);
    };

    @Override
    public AccountBuilderInterface getAccountFactory(InputChangedCallbackInterface callback) {
        return new PremiumizeAccountFactory(callback);
    }

    @Override
    protected PluginConfigPanelNG createConfigPanel() {
        return new UsenetConfigPanel() {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean showKeyHandler(KeyHandler<?> keyHandler) {
                return "enablepairinglogin".equals(keyHandler.getKey()) || "enableboosterpointsunlimitedtrafficworkaround".equals(keyHandler.getKey());
            }

            @Override
            protected boolean useCustomUI(KeyHandler<?> keyHandler) {
                return !"enablepairinglogin".equals(keyHandler.getKey()) && !"enableboosterpointsunlimitedtrafficworkaround".equals(keyHandler.getKey());
            }

            @Override
            protected void initAccountConfig(PluginForHost plgh, Account acc, Class<? extends AccountConfigInterface> cf) {
                super.initAccountConfig(plgh, acc, cf);
                extend(this, getHost(), getAvailableUsenetServer(), getAccountJsonConfig(acc));
            }
        };
    }

    @Override
    public PremiumizeMeConfigInterface getAccountJsonConfig(final Account acc) {
        return (PremiumizeMeConfigInterface) super.getAccountJsonConfig(acc);
    }

    @Override
    public LazyPlugin.FEATURE[] getFeatures() {
        return new LazyPlugin.FEATURE[] { LazyPlugin.FEATURE.MULTIHOST, LazyPlugin.FEATURE.USENET };
    }

    @Override
    public boolean supportsUsenet(final Account account) {
        /*
         * 2019-12-18: At the moment usenet client support is only working with apikey login, not yet in pairing login mode. Premiumize is
         * working on making this possible in pairing mode as well.
         */
        return !usePairingLogin(account);
        // return true;
    }

    @Override
    public boolean usePairingLogin(final Account account) {
        /**
         * 2021-01-29: Hardcoded-disabled this because API changes would be required to make Usenet work when logged in via this method.
         * Also some users enabled this by mistake and then failed to login (WTF)
         */
        if (false && this.getAccountJsonConfig(account).isEnablePairingLogin()) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean isBoosterPointsUnlimitedTrafficWorkaroundActive(final Account account) {
        if (this.getAccountJsonConfig(account).isEnableBoosterPointsUnlimitedTrafficWorkaround()) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public List<UsenetServer> getAvailableUsenetServer() {
        final List<UsenetServer> ret = new ArrayList<UsenetServer>();
        ret.addAll(UsenetServer.createServerList("usenet.premiumize.me", false, 119));
        ret.addAll(UsenetServer.createServerList("usenet.premiumize.me", true, 563));
        return ret;
    }

    public static class PremiumizeAccountFactory extends MigPanel implements AccountBuilderInterface {
        /**
         *
         */
        private static final long serialVersionUID = 1L;
        private final String      APIKEYHELP       = "Enter your API Key";
        private final JLabel      apikeyLabel;

        private String getPassword() {
            if (this.pass == null) {
                return null;
            } else {
                return new String(this.pass.getPassword());
            }
        }

        public boolean updateAccount(Account input, Account output) {
            if (!StringUtils.equals(input.getUser(), output.getUser())) {
                output.setUser(input.getUser());
                return true;
            } else if (!StringUtils.equals(input.getPass(), output.getPass())) {
                output.setPass(input.getPass());
                return true;
            } else {
                return false;
            }
        }

        private final ExtPasswordField pass;

        public PremiumizeAccountFactory(final InputChangedCallbackInterface callback) {
            super("ins 0, wrap 2", "[][grow,fill]", "");
            add(new JLabel("Click here to find your API Key:"));
            add(new JLink("https://www.premiumize.me/account"));
            add(apikeyLabel = new JLabel("API Key:"));
            add(this.pass = new ExtPasswordField() {
                @Override
                public void onChanged() {
                    callback.onChangedInput(this);
                }
            }, "");
            pass.setHelpText(APIKEYHELP);
        }

        @Override
        public JComponent getComponent() {
            return this;
        }

        @Override
        public void setAccount(Account defaultAccount) {
            if (defaultAccount != null) {
                // name.setText(defaultAccount.getUser());
                pass.setText(defaultAccount.getPass());
            }
        }

        @Override
        public boolean validateInputs() {
            final String password = getPassword();
            if (isAPIKEY(password)) {
                apikeyLabel.setForeground(Color.BLACK);
                return true;
            } else {
                apikeyLabel.setForeground(Color.RED);
                return false;
            }
        }

        @Override
        public Account getAccount() {
            return new Account(null, getPassword());
        }
    }

    @Override
    protected MultiHosterManagement getMultiHosterManagement() {
        return mhm;
    }
}