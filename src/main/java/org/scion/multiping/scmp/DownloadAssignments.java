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

package org.scion.multiping.scmp;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.scion.jpan.ScionUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class DownloadAssignments {
  private static final String HTTPS_URL =
      "https://docs.anapaya.net/en/latest/resources/isd-as-assignments/";

  public static void main(String[] args) throws IOException {
    new DownloadAssignments().jsoup();
  }

  public static List<ParseAssignments.HostEntry> getList() {
    DownloadAssignments pa = new DownloadAssignments();
    try {
      return pa.jsoup();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  public List<ParseAssignments.HostEntry> jsoup() throws IOException {
    List<ParseAssignments.HostEntry> result = new ArrayList<>(100);
    Document doc = Jsoup.connect(HTTPS_URL).get();
    for (Element bc : doc.body().getElementsByAttributeValue("id", "isd-membership")) {
      // System.out.println("eee " + bc);
      for (Element bc2 : bc.getElementsByTag("tbody")) {
        // System.out.println("eee2 " + bc2);
        for (Element bc3 : bc2.children()) {
          // System.out.println("eee3 " + bc3);
          String isdAs = bc3.child(0).getElementsByTag("p").text();
          String name = bc3.child(1).getElementsByTag("p").text();
          // System.out.println(isdAs + " " + name);
          result.add(new ParseAssignments.HostEntry(ScionUtil.parseIA(isdAs), name));
          //                    for (Element bc4 : bc3.children()) {
          //                        System.out.println("eee4 " + bc4.getElementsByTag("p").text());
          //                    }
        }
      }
    }
    return result;
  }
}
