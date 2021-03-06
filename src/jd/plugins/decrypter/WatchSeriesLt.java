//jDownloader - Downloadmanager
//Copyright (C) 2009  JD-Team support@jdownloader.org
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

package jd.plugins.decrypter;

import java.util.ArrayList;

import jd.PluginWrapper;
import jd.controlling.ProgressController;
import jd.plugins.CryptedLink;
import jd.plugins.DecrypterPlugin;
import jd.plugins.DownloadLink;
import jd.plugins.PluginForDecrypt;

@DecrypterPlugin(revision = "$Revision$", interfaceVersion = 2, names = { "watchseries.lt" }, urls = { "https?://(www\\.)?watch(tv)?series(free)?\\.(lt|ag|ch|sx|to|)/open/cale/.+\\.html" })
public class WatchSeriesLt extends PluginForDecrypt {

    public WatchSeriesLt(PluginWrapper wrapper) {
        super(wrapper);
    }

    public ArrayList<DownloadLink> decryptIt(CryptedLink param, ProgressController progress) throws Exception {
        ArrayList<DownloadLink> decryptedLinks = new ArrayList<DownloadLink>();
        final String parameter = param.toString();
        br.getPage(parameter);
        if (br.containsHTML(">Um, Where did the page go|You either took a wrong turn or the site is screwed")) {
            logger.info("Link offline: " + parameter);
            return decryptedLinks;
        }
        if (parameter.matches(".+/open/cale/.+")) {
            final String[] finallinks = br.getRegex("myButton.*?\" href=\"(https?://[^<>\"]*?)\">(Click Here)?").getColumn(0);
            for (final String finallink : finallinks) {
                if (finallink == null) {
                    logger.warning("Decrypter broken 1 for link: " + parameter);
                    return null;
                }
                logger.info("Finallink: " + finallink);
                decryptedLinks.add(createDownloadlink(finallink));
            }
        }
        return decryptedLinks;
    }
}