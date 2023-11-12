package jd.plugins.hoster;

import org.jdownloader.plugins.components.google.GoogleAccountConfig;
import org.jdownloader.plugins.components.google.GoogleHelper;
import org.jdownloader.plugins.config.PluginConfigInterface;

import jd.PluginWrapper;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.PluginConfigPanelNG;
import jd.plugins.PluginForHost;

@HostPlugin(revision = "$Revision: 47365 $", interfaceVersion = 3, names = { "recaptcha.google.com" }, urls = { "google://.+" })
public class GooglePremium extends PluginForHost {
    @Override
    public Boolean siteTesterDisabled() {
        // no tests required, dummy subdomain
        return Boolean.TRUE;
    }

    @Override
    public String getDescription() {
        return "Used for the purpose of ReCaptcha!";
    }

    @Override
    public String rewriteHost(String host) {
        if ("google.com (Recaptcha)".equals(host) || "recaptcha.google.com".equals(host)) {
            return "recaptcha.google.com";
        }
        return super.rewriteHost(host);
    }

    @Override
    public String getAGBLink() {
        return "http://www.google.com/policies/terms/";
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        final GoogleHelper helper = new GoogleHelper(br);
        helper.login(account, true);
        final AccountInfo ai = new AccountInfo();
        ai.setValidUntil(-1);
        return ai;
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return 1;
    }

    @Override
    public Class<GoogleAccountConfig> getAccountConfigInterface(Account account) {
        return GoogleAccountConfig.class;
    }

    public GooglePremium(PluginWrapper wrapper) {
        super(wrapper);
        /*
         * 2020-07-027: Disabled as it is not needed anymore (for now) and is only confusing users. Google login is still possible via
         * hosts: youtube.com and drive.google.com
         */
        // this.enablePremium("https://accounts.google.com/signup");
    }

    @Override
    public AvailableStatus requestFileInformation(DownloadLink downloadLink) throws Exception {
        return AvailableStatus.TRUE;
    }

    @Override
    public void handleFree(final DownloadLink downloadLink) throws Exception {
        handlePremium(downloadLink, null);
    }

    @Override
    public Class<? extends PluginConfigInterface> getConfigInterface() {
        return null;
    }

    @Override
    public void handlePremium(DownloadLink downloadLink, Account account) throws Exception {
        throw new Exception("Not implemented");
    }

    @Override
    public boolean isProxyRotationEnabledForLinkChecker() {
        return super.isProxyRotationEnabledForLinkChecker();
    }

    @Override
    public void extendAccountSettingsPanel(Account acc, PluginConfigPanelNG panel) {
    }

    @Override
    public void resetDownloadlink(DownloadLink downloadLink) {
    }

    @Override
    public void resetPluginGlobals() {
    }

    @Override
    public void reset() {
    }
}
