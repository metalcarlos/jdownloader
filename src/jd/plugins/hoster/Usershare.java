//    jDownloader - Downloadmanager
//    Copyright (C) 2009  JD-Team support@jdownloader.org
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

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;

import jd.PluginWrapper;
import jd.nutils.encoding.Encoding;
import jd.parser.Regex;
import jd.parser.html.Form;
import jd.plugins.Account;
import jd.plugins.AccountInfo;
import jd.plugins.DownloadLink;
import jd.plugins.DownloadLink.AvailableStatus;
import jd.plugins.HostPlugin;
import jd.plugins.LinkStatus;
import jd.plugins.Plugin;
import jd.plugins.PluginException;
import jd.plugins.PluginForHost;
import jd.utils.JDUtilities;

import org.appwork.utils.formatter.SizeFormatter;
import org.appwork.utils.formatter.TimeFormatter;

@HostPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "usershare.net" }, urls = { "http(s)?://[\\w\\.]*?usershare\\.net/(.*?/[0-9a-z]{12}|[0-9a-z]{12})" }, flags = { 2 })
public class Usershare extends PluginForHost {

    public Usershare(PluginWrapper wrapper) {
        super(wrapper);
        this.enablePremium(COOKIE_HOST + "/premium.html");
    }

    private static final String COOKIE_HOST   = "http://usershare.net";
    private static final String PASSWORDTEXT0 = "<br><b>Password:</b> <input";
    private static final String PASSWORDTEXT1 = "<br><b>Passwort:</b> <input";

    @Override
    public String getAGBLink() {
        return "http://usershare.net/tos.html";
    }

    @Override
    public void correctDownloadLink(DownloadLink link) {
        link.setUrlDownload(link.getDownloadURL().replaceFirst("https", "http"));
    }

    @Override
    public void handleFree(DownloadLink link) throws Exception {
        requestFileInformation(link);
        String passCode = null;
        String linkurl = null;
        checkErrors(link, false, passCode, false);
        br.setFollowRedirects(false);
        boolean password = false;
        if (br.containsHTML(PASSWORDTEXT0) || br.containsHTML(PASSWORDTEXT1)) {
            password = true;
            logger.info("The downloadlink seems to be password protected.");
        }
        if (br.containsHTML("api\\.recaptcha\\.net") || br.containsHTML("google.com/recaptcha/api/")) {
            // Some hosters also got commentfields with captchas, therefore is
            // the !br.contains...check Exampleplugin:
            // FileGigaCom
            logger.info("Detected captcha method \"Re Captcha\" for this host");
            PluginForHost recplug = JDUtilities.getPluginForHost("DirectHTTP");
            jd.plugins.hoster.DirectHTTP.Recaptcha rc = ((DirectHTTP) recplug).getReCaptcha(br);
            rc.parse();
            rc.load();
            File cf = rc.downloadCaptcha(getLocalCaptchaFile());
            String c = getCaptchaCode(cf, link);
            if (password) {
                passCode = handlePassword(passCode, rc.getForm(), link);
            }
            rc.setCode(c);
            logger.info("Put captchacode " + c + " obtained by captcha metod \"Re Captcha\" in the form and submitted it.");
            checkErrors(link, true, passCode, false);
            linkurl = getDllink();
        } else {
            if (password) {
                logger.info("The downloadlink seems to be password protected.");
                Form pwform = br.getFormbyProperty("name", "F1");
                if (pwform == null) throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
                passCode = handlePassword(passCode, pwform, link);
                pwform.put("password", passCode);
                logger.info("Put password \"" + passCode + "\" entered by user in the DLForm and submitted it.");
                br.submitForm(pwform);
                checkErrors(link, true, passCode, false);
                linkurl = br.getRedirectLocation();
            } else {
                linkurl = getDllink();
            }
        }
        if (passCode != null) {
            link.setProperty("pass", passCode);
        }
        if (linkurl == null) throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        br.setDebug(true);
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, linkurl, true, 1);
        dl.startDownload();
    }

    private String getDllink() {
        String linkurl = br.getRegex("\"(http://[a-zA-Z0-9]+\\.usershare\\.net/files/.*?/.*?/.*?)\"").getMatch(0);
        if (linkurl == null) {
            linkurl = br.getRegex("href=\"(http://[0-9]+\\..*?:[0-9]+/d/.*?/.*?)\"").getMatch(0);
        }
        return linkurl;
    }

    @Override
    public AccountInfo fetchAccountInfo(Account account) throws Exception {
        AccountInfo ai = new AccountInfo();
        try {
            login(account);
        } catch (PluginException e) {
            account.setValid(false);
            return ai;
        }
        String space = br.getRegex(Pattern.compile("<td>Used space:</td>.*?<td.*?b>(.*?)of.*?Mb</b>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE)).getMatch(0);
        if (space != null) ai.setUsedSpace(space.trim() + " Mb");
        String points = br.getRegex(Pattern.compile("<td>You have collected:</td.*?b>(.*?)premium points", Pattern.CASE_INSENSITIVE)).getMatch(0);
        if (points != null) {
            // Who needs half points ? If we have a dot in the points, just
            // remove it
            if (points.contains(".")) {
                String dot = new Regex(points, ".*?(\\.(\\d+))").getMatch(0);
                points = points.replace(dot, "");
            }
            ai.setPremiumPoints(Long.parseLong(points.trim()));
        }
        account.setValid(true);
        String availabletraffic = new Regex(br.toString(), "Traffic available.*?:</TD><TD><b>(.*?)</b>").getMatch(0);
        if (availabletraffic != null) {
            ai.setTrafficLeft(SizeFormatter.getSize(availabletraffic));
        } else {
            ai.setUnlimitedTraffic();
        }
        String expire = new Regex(br.toString(), "<td>Premium-Account expire:</td>.*?<td>(.*?)</td>").getMatch(0);
        if (expire == null) {
            ai.setExpired(true);
            account.setValid(false);
            return ai;
        } else {
            expire = expire.replaceAll("(<b>|</b>)", "");
            ai.setValidUntil(TimeFormatter.getMilliSeconds(expire, "dd MMMM yyyy", null));
        }
        ai.setStatus("Premium User");
        return ai;
    }

    private void login(Account account) throws Exception {
        this.setBrowserExclusive();
        br.setCookie(COOKIE_HOST, "lang", "english");
        br.getPage(COOKIE_HOST + "/login.html");
        Form loginform = br.getForm(1);
        if (loginform == null) throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        loginform.put("login", Encoding.urlEncode(account.getUser()));
        loginform.put("password", Encoding.urlEncode(account.getPass()));
        br.submitForm(loginform);
        br.getPage(COOKIE_HOST + "/?op=my_account");
        if (br.getCookie(COOKIE_HOST, "login") == null || br.getCookie(COOKIE_HOST, "xfss") == null) throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
        if (!br.containsHTML("Premium-Account expire") && !br.containsHTML(">Renew premium<")) throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_DISABLE);
    }

    @Override
    public void handlePremium(DownloadLink link, Account account) throws Exception {
        String passCode = null;
        requestFileInformation(link);
        login(account);
        br.setFollowRedirects(false);
        br.getPage(link.getDownloadURL());
        String dllink = br.getRedirectLocation();
        if (dllink == null) {
            Form DLForm = br.getFormbyProperty("name", "F1");
            if (DLForm == null) throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
            if (br.containsHTML("(<b>Passwort:</b>|<b>Password:</b>)")) {
                logger.info("The downloadlink seems to be password protected.");
                if (link.getStringProperty("pass", null) == null) {
                    passCode = Plugin.getUserInput("Password?", link);
                } else {
                    /* gespeicherten PassCode holen */
                    passCode = link.getStringProperty("pass", null);
                }
                DLForm.put("password", passCode);
                logger.info("Put password \"" + passCode + "\" entered by user in the DLForm and submitted it.");
            }
            br.submitForm(DLForm);
            if (br.containsHTML("(Wrong password|<b>Passwort:</b>|<b>Password:</b>)")) {
                logger.warning("Wrong password, the entered password \"" + passCode + "\" is wrong, retrying...");
                link.setProperty("pass", null);
                throw new PluginException(LinkStatus.ERROR_RETRY);
            }
            dllink = br.getRedirectLocation();
            if (dllink == null) dllink = getDllink();
        }
        if (dllink == null) {
            logger.warning("Final downloadlink (String is \"dllink\") regex didn't match!");
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        logger.info("Final downloadlink = " + dllink + " starting the download...");
        dl = jd.plugins.BrowserAdapter.openDownload(br, link, dllink, true, 0);
        if (passCode != null) {
            link.setProperty("pass", passCode);
        }
        if (dl.getConnection().getContentType() != null && dl.getConnection().getContentType().contains("html")) {
            logger.warning("The final dllink seems not to be a file!");
            br.followConnection();
            throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        }
        dl.startDownload();
    }

    @Override
    public int getMaxSimultanPremiumDownloadNum() {
        return -1;
    }

    @Override
    public AvailableStatus requestFileInformation(DownloadLink parameter) throws Exception {
        this.setBrowserExclusive();
        br.setCookie(COOKIE_HOST, "lang", "english");
        br.getPage(parameter.getDownloadURL());
        if (br.containsHTML("No such user exist") || br.containsHTML("File Not Found")) throw new PluginException(LinkStatus.ERROR_FILE_NOT_FOUND);
        String filename = br.getRegex("class=\"hdr\"><TD colspan=2>(.*?)</TD>").getMatch(0);
        if (filename == null) {
            filename = br.getRegex("<b>Filename:</b></td><td nowrap>(.*?)</b>").getMatch(0);
            if (filename == null) {
                filename = br.getRegex("<h3>Download File:(.*?)</h3>").getMatch(0);
                if (filename == null) filename = br.getRegex("<title>Download(.*?)</title>").getMatch(0);
            }
        }
        String filesize = br.getRegex("Size:</b></td><td>(.*?)<small>").getMatch(0);
        if (filesize == null) filesize = br.getRegex("label>Size:</label> <span>(.*?)</span>").getMatch(0);
        if (filename == null) throw new PluginException(LinkStatus.ERROR_PLUGIN_DEFECT);
        parameter.setName(filename.trim());
        if (filesize != null) parameter.setDownloadSize(SizeFormatter.getSize(filesize));
        return AvailableStatus.TRUE;
    }

    public String handlePassword(String passCode, Form pwform, DownloadLink thelink) throws IOException, PluginException {
        if (thelink.getStringProperty("pass", null) == null) {
            passCode = Plugin.getUserInput("Password?", thelink);
        } else {
            /* gespeicherten PassCode holen */
            passCode = thelink.getStringProperty("pass", null);
        }
        pwform.put("password", passCode);
        logger.info("Put password \"" + passCode + "\" entered by user in the DLForm.");
        return passCode;
    }

    public void checkErrors(DownloadLink theLink, boolean checkAll, String passCode, boolean loggedIn) throws NumberFormatException, PluginException {
        if (checkAll) {
            if (br.containsHTML("<br><b>Password:</b> <input") || br.containsHTML("<br><b>Passwort:</b> <input") || br.containsHTML("Wrong password")) {
                logger.warning("Wrong password, the entered password \"" + passCode + "\" is wrong, retrying...");
                theLink.setProperty("pass", null);
                throw new PluginException(LinkStatus.ERROR_RETRY);
            }
            if (br.containsHTML("Wrong captcha")) {
                logger.warning("Wrong captcha or wrong password!");
                throw new PluginException(LinkStatus.ERROR_CAPTCHA);
            }
        }
        // Some waittimes...
        if (br.containsHTML("You have to wait")) {
            if (loggedIn) throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
            int minutes = 0, seconds = 0, hours = 0;
            String tmphrs = br.getRegex("You have to wait.*?\\s+(\\d+)\\s+hours?").getMatch(0);
            if (tmphrs != null) hours = Integer.parseInt(tmphrs);
            String tmpmin = br.getRegex("You have to wait.*?\\s+(\\d+)\\s+minutes?").getMatch(0);
            if (tmpmin != null) minutes = Integer.parseInt(tmpmin);
            String tmpsec = br.getRegex("You have to wait.*?\\s+(\\d+)\\s+seconds?").getMatch(0);
            if (tmpsec != null) seconds = Integer.parseInt(tmpsec);
            int waittime = ((3600 * hours) + (60 * minutes) + seconds + 1) * 1000;
            if (waittime != 0) {
                logger.info("Detected waittime #1, waiting " + waittime + " milliseconds");
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, waittime);
            } else {
                logger.info("Waittime regexes seem to be broken");
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED);
            }
        }
        if (br.containsHTML("You have reached the download-limit")) {
            if (loggedIn) throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
            String tmphrs = br.getRegex("\\s+(\\d+)\\s+hours?").getMatch(0);
            String tmpmin = br.getRegex("\\s+(\\d+)\\s+minutes?").getMatch(0);
            String tmpsec = br.getRegex("\\s+(\\d+)\\s+seconds?").getMatch(0);
            String tmpdays = br.getRegex("\\s+(\\d+)\\s+days?").getMatch(0);
            if (tmphrs == null && tmpmin == null && tmpsec == null && tmpdays == null) {
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 60 * 60 * 1000l);
            } else {
                int minutes = 0, seconds = 0, hours = 0, days = 0;
                if (tmphrs != null) hours = Integer.parseInt(tmphrs);
                if (tmpmin != null) minutes = Integer.parseInt(tmpmin);
                if (tmpsec != null) seconds = Integer.parseInt(tmpsec);
                if (tmpdays != null) days = Integer.parseInt(tmpdays);
                int waittime = ((days * 24 * 3600) + (3600 * hours) + (60 * minutes) + seconds + 1) * 1000;
                logger.info("Detected waittime #2, waiting " + waittime + "milliseconds");
                throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, waittime);
            }
        }
        if (br.containsHTML("You're using all download slots for IP")) {
            if (loggedIn) throw new PluginException(LinkStatus.ERROR_PREMIUM, PluginException.VALUE_ID_PREMIUM_TEMP_DISABLE);
            throw new PluginException(LinkStatus.ERROR_IP_BLOCKED, null, 10 * 60 * 1001l);
        }
        if (br.containsHTML("Error happened when generating Download Link")) throw new PluginException(LinkStatus.ERROR_TEMPORARILY_UNAVAILABLE, "Server error!", 10 * 60 * 1000l);
        // Errorhandling for only-premium links
        if (br.containsHTML(" can download files up to ") || br.containsHTML("Upgrade your account to download bigger files") || br.containsHTML("This file reached max downloads") || br.containsHTML(">Upgrade your account to download larger files")) {
            String filesizelimit = br.getRegex("You can download files up to(.*?)only").getMatch(0);
            if (filesizelimit != null) {
                filesizelimit = filesizelimit.trim();
                logger.warning("As free user you can download files up to " + filesizelimit + " only");
                throw new PluginException(LinkStatus.ERROR_FATAL, "Free users can only download files up to " + filesizelimit);
            } else {
                logger.warning("Only downloadable via premium");
                throw new PluginException(LinkStatus.ERROR_FATAL, "Only downloadable via premium");
            }
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public void resetDownloadlink(DownloadLink link) {
    }

    @Override
    public int getMaxSimultanFreeDownloadNum() {
        return 2;
    }
}
