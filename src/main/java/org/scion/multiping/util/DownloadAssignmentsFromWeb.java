// Copyright 2024 ETH Zurich
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.scion.multiping.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.scion.jpan.ScionUtil;

public class DownloadAssignmentsFromWeb {
  private static final String HTTPS_URL =
      "https://docs.anapaya.net/en/latest/resources/isd-as-assignments/";

  public static void main(String[] args) throws IOException {
    new DownloadAssignmentsFromWeb().jsoup();
  }

  public static List<ParseAssignments.HostEntry> getList() {
    DownloadAssignmentsFromWeb pa = new DownloadAssignmentsFromWeb();
    try {
      return pa.jsoup();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public List<ParseAssignments.HostEntry> jsoup() throws IOException {
    List<ParseAssignments.HostEntry> result = new ArrayList<>(100);
    Document doc = Jsoup.connect(HTTPS_URL).get();

    for (Element table : doc.getElementsByTag("table")) {
      for (Element isd_as : table.getElementsContainingText("ISD-AS")) {
        if ("table".equals(isd_as.tagName())) {
          for (Element te : table.children()) {
            if ("thead".equals(te.tagName())) {
              continue;
            }
            for (Element te2 : te.children()) {
              // System.out.println("     te3: " + te2.tag());
              String isdAs = te2.child(0).getElementsByTag("td").text();
              String name = te2.child(1).getElementsByTag("td").text();
              // System.out.println(isdAs + " " + name);
              result.add(new ParseAssignments.HostEntry(ScionUtil.parseIA(isdAs), name));
            }
          }
        }
      }
    }

    return result;
  }
}
