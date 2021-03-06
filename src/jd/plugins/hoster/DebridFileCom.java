//jDownloader - Downloadmanager
//Copyright (C) 2013  JD-Team support@jdownloader.org
//
//This program is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.
//
//This program is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
//GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License
//along with this program.  If not, see <http://www.gnu.org/licenses/>.
package jd.plugins.hoster;

import java.io.IOException;
import java.util.Arrays;

import org.appwork.utils.StringUtils;
import org.appwork.utils.formatter.SizeFormatter;
import org.jdownloader.captcha.v2.challenge.recaptcha.v2.CaptchaHelperHostPluginRecaptchaV2;
import org.jdownloader.plugins.controller.host.LazyHostPlugin.FEATURE;

import jd.PluginWrapper;
import jd.config.Property;
import jd.http.Browser;
import jd.http.Cookies;
import jd.http.URLConnectionAdapter;
import jd.nutils.encoding.Encoding;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.Account.AccountType;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.plugins.components.MultiHosterManagement;

@HostPlugin(revision = "$Revision$", interfaceVersion = 3, names = { "debrid-file.com" }, urls = { "" })
public class DebridFileCom extends PluginForHost {
    /* This is a "updated" version of website tout-debrid.ch */
    private static final String          WEBSITE_BASE                 = "https://debrid-file.com";
    private static MultiHosterManagement mhm                          = new MultiHosterManagement("debrid-file.com");
    private static final boolean         account_PREMIUM_resume       = true;
    /** 2020-03-21: phg: In my tests, it is OK for the chunkload with the value of 5 */
    private static final int             account_PREMIUM_maxchunks    = -5;
    private static final int             account_PREMIUM_maxdownloads = -1;
    private static final boolean         account_FREE_resume          = true;
    private static final int             account_FREE_maxchunks       = 1;
    private static final int             account_FREE_maxdownloads    = 1;

    @SuppressWarnings("deprecation")
    public DebridFileCom(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium("http://debrid-file.com/dashboard.php?pages=paiement");
    }

    @Override
    public String getAGBLink() {
        return "http://debrid-file.com/dashboard.php?pages=faq";
    }

    private Browser newBrowser() {
        br = new Browser();
        br.setCookiesExclusive(true);
        br.getHeaders().put("User-Agent", "JDownloader");
        return br;
    }

    @Override
    public AvailableStatus requestFileInformation(final DownloadLink link) throws IOException, PluginException {
        return AvailableStatus.UNCHECKABLE;
    }

    @Override
    public void handleFree(final DownloadLink link) throws Exception, PluginException {
        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
    }

    @Override
    public void handlePremium(final DownloadLink link, final Account account) throws Exception {
        throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_ONLY);
    }

    private void handleDLMultihoster(final Account account, final DownloadLink link, final String dllink) throws Exception {
        if (StringUtils.isEmpty(dllink)) {
            mhm.handleErrorGeneric(account, link, "dllinknull", 50, 5 * 60 * 1000l);
        }
        link.setProperty(this.getHost() + "directlink", dllink);
        final boolean resume;
        final int maxchunks;
        if (account.getType() == AccountType.PREMIUM) {
            resume = account_PREMIUM_resume;
            maxchunks = account_PREMIUM_maxchunks;
        } else {
            resume = account_FREE_resume;
            maxchunks = account_FREE_maxchunks;
        }
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, resume, maxchunks);
        if (dl.getConnection().getContentType().contains("html")) {
            br.followConnection(true);
            mhm.handleErrorGeneric(account, link, "unknown_dl_error", 10, 5 * 60 * 1000l);
        }
        this.dl.startDownload();
    }

    @Override
    public FEATURE[] getFeatures() {
        return new FEATURE[] { FEATURE.MULTIHOST };
    }

    @Override
    public void handleMultiHost(final DownloadLink link, final Account account) throws Exception {
        this.br = newBrowser();
        mhm.runCheck(account, link);
        String dllink = checkDirectLink(link, this.getHost() + "directlink");
        br.setFollowRedirects(true);
        if (dllink == null) {
            this.loginWebsite(account);
            br.postPage("/getlink2.php", "link=" + Encoding.urlEncode(link.getDefaultPlugin().buildExternalDownloadURL(link, this)) + "&pass=" + link.getDownloadPassword());
            dllink = br.getRegex("target='_blank' href='(http[^<>\"\\']+)'>").getMatch(0);
            if (dllink == null) {
                mhm.handleErrorGeneric(account, link, "dllinknull", 50, 5 * 60 * 1000l);
            }
        }
        handleDLMultihoster(account, link, dllink);
    }

    private String checkDirectLink(final DownloadLink downloadLink, final String property) {
        String dllink = downloadLink.getStringProperty(property);
        if (dllink != null) {
            URLConnectionAdapter con = null;
            try {
                final Browser br2 = br.cloneBrowser();
                br2.setFollowRedirects(true);
                con = br2.openHeadConnection(dllink);
                if (con.getContentType().contains("html") || !con.isOK() || con.getLongContentLength() == -1) {
                    downloadLink.setProperty(property, Property.NULL);
                    dllink = null;
                }
            } catch (final Exception e) {
                logger.log(e);
                downloadLink.setProperty(property, Property.NULL);
                dllink = null;
            } finally {
                if (con != null) {
                    con.disconnect();
                }
            }
        }
        return dllink;
    }

    @Override
    public AccountInfo fetchAccountInfo(final Account account) throws Exception {
        this.br = newBrowser();
        final AccountInfo ai = new AccountInfo();
        loginWebsite(account);
        if (br.getURL() == null || !br.getURL().contains("/dashboard.php?pages=service&hl=en")) {
            br.getPage(WEBSITE_BASE + "/dashboard.php?pages=service&hl=en");
        }
        final String premiumDaysStr = br.getRegex("Premium\\s*:\\s*(\\d+)\\s*<small").getMatch(0);
        String trafficleftStr = br.getRegex("</small><b>(\\d+(\\.|)\\d{1,2} [A-Za-z]+)</b>").getMatch(0);
        if (premiumDaysStr == null) {
            /* Free or plugin failure */
            /*
             * account.setType(AccountType.FREE); ai.setTrafficMax("10 GB"); ai.setStatus("Free Account");
             * account.setMaxSimultanDownloads(account_FREE_maxdownloads); account.setValid(true);
             */
            // 2020.03.27 : phg : Free Accounts are not allowed for this plugin
            throw new PluginException(LinkStatus.ERROR_PREMIUM, "Plugin for premium accounts only");
        } else {
            /* Premium */
            account.setType(AccountType.PREMIUM);
            ai.setStatus("Premium account");
            account.setMaxSimultanDownloads(account_PREMIUM_maxdownloads);
            ai.setTrafficMax("300 GB");
            ai.setValidUntil(System.currentTimeMillis() + Long.parseLong(premiumDaysStr) * 24 * 60 * 60 * 1000l, this.br);
        }
        if (trafficleftStr == null) {
            /* Downloads are not possible if the traffic has not be retrieved */
            ai.setTrafficLeft(0);
        } else {
            ai.setTrafficLeft(SizeFormatter.getSize(trafficleftStr));
        }
        /*
         * Get list of supported hosts.
         */
        final String[] hosts = br.getRegex("/hostlar/([^<>\"]+)\\.png\"").getColumn(0);
        ai.setMultiHostSupport(this, Arrays.asList(hosts));
        account.setConcurrentUsePossible(true);
        return ai;
    }

    private void loginWebsite(final Account account) throws IOException, PluginException, InterruptedException {
        synchronized (account) {
            try {
                br.setFollowRedirects(true);
                final Cookies cookies = account.loadCookies("");
                if (cookies != null) {
                    logger.info("Trying to login via cookies");
                    br.setCookies(WEBSITE_BASE, cookies);
                    br.getPage(WEBSITE_BASE + "/dashboard.php?pages=service&hl=en");
                    if (this.isLoggedIN()) {
                        logger.info("Cookie login successful");
                        account.saveCookies(br.getCookies(br.getHost()), "");
                        return;
                    } else {
                        logger.info("Cookie login failed");
                    }
                }
                logger.info("Performing full login");
                br.getPage(WEBSITE_BASE + "/dashboard.php?pages=login");
                final Form loginform = br.getFormbyKey("pass");
                if (loginform == null) {
                    throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                }
                loginform.put("add", Encoding.urlEncode(account.getUser()));
                loginform.put("pass", Encoding.urlEncode(account.getPass()));
                if (CaptchaHelperHostPluginRecaptchaV2.containsRecaptchaV2Class(loginform)) {
                    /* 2020-03-19: reCaptchaV2 is always required but they may make it optional in the future. */
                    final DownloadLink dlinkbefore = this.getDownloadLink();
                    try {
                        final DownloadLink dl_dummy;
                        if (dlinkbefore != null) {
                            dl_dummy = dlinkbefore;
                        } else {
                            dl_dummy = new DownloadLink(this, "Account", this.getHost(), "https://" + account.getHoster(), true);
                            this.setDownloadLink(dl_dummy);
                        }
                        final String recaptchaV2Response = new CaptchaHelperHostPluginRecaptchaV2(this, br).getToken();
                        loginform.put("recaptcha", Encoding.urlEncode(recaptchaV2Response));
                    } finally {
                        this.setDownloadLink(dlinkbefore);
                    }
                }
                if (loginform.getAction() == null || !loginform.getAction().startsWith("http") || !loginform.getAction().startsWith("/")) {
                    /* 2020-03-19: Action = javascript --> "javascript:gonder();" --> We need to correct this */
                    loginform.setAction("/verif.php");
                }
                br.submitForm(loginform);
                if (!isLoggedIN()) {
                    throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
                }
                account.saveCookies(br.getCookies(br.getHost()), "");
            } catch (PluginException e) {
                if (e.getLinkStatus() == LinkStatus.ERROR_PREMIUM) {
                    account.clearCookies("");
                }
                throw e;
            }
        }
    }

    private boolean isLoggedIN() throws PluginException {
        if (br.containsHTML("403") && br.containsHTML("Forbidden")) {
            /*
             * 2020-03-27: What does this mean? Is this supposed to be a temporary error? If so, you should use e.g. throw new
             * AccountUnavailableException("Error 403 'blocked by debrid-file'", 10 * 60 * 1000);
             *
             * 2020-03-27 : phg : This a temporary fix as we are not supposed to have a 403 error and we must fix the 403. I let the
             * previous code as it is not a temporary account error but a fatal plugin error
             */
            throw new PluginException(LinkStatus.ERROR_PREMIUM, "Blocked by Debrid-File");
        }
        boolean bCookieOK = br.getCookie(br.getHost(), "loginhash", Cookies.NOTDELETEDPATTERN) != null;
        if (bCookieOK) {
            if (br.containsHTML("You must be logged")) {
                bCookieOK = false;
            }
        }
        return bCookieOK;
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }
}