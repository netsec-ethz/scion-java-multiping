// Copyright 2026 ETH Zurich
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

import static org.scion.multiping.util.Util.*;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.util.*;
import org.jsoup.Jsoup;
import org.scion.jpan.Constants;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.bootstrap.DNSHelper;

/**
 * Downloads all TRCs known to a control service and extracts the ISD numbers and the core and
 * authoritative ASes of each ISD.
 *
 * <p>This uses the HTTP bootstrap/discovery API of the control service (default port 8041):
 *
 * <ul>
 *   <li>{@code GET /trcs} lists the IDs (ISD, base, serial) of all TRCs the CS knows.
 *   <li>{@code GET /trcs/isd<I>-b<B>-s<S>} returns the decoded TRC as JSON.
 * </ul>
 *
 * Note that a CS usually only knows the TRCs of ISDs that it has interacted with (e.g. via
 * beaconing or segment verification). The result is therefore not necessarily the full list of
 * ISDs. Also, TRCs only contain core/authoritative ASes, not the non-core ASes of an ISD.
 *
 * <p>Usage: {@code DownloadTrcs [<host>[:<port>]]}. Without argument, the server is taken from
 * {@code SCION_BOOTSTRAP_HOST} / {@code org.scion.bootstrap.host} or found via DNS NAPTR.
 */
public class DownloadTrcs {
  private static final int DEFAULT_PORT = 8041;
  private static final int TIMEOUT_MS = 10_000;

  private final Gson gson = new Gson();
  private final String server;

  public DownloadTrcs(String hostAndPort) {
    this.server = hostAndPort.contains(":") ? hostAndPort : hostAndPort + ":" + DEFAULT_PORT;
  }

  public static void main(String[] args) throws IOException {
    String server = args.length > 0 ? args[0] : findServer();
    println("Downloading TRCs from " + server);
    List<Trc> trcs = new DownloadTrcs(server).downloadTrcs();
    for (Trc trc : trcs) {
      println(
          "ISD "
              + trc.getIsd()
              + "  (b"
              + trc.getBase()
              + "-s"
              + trc.getSerial()
              + ")  \""
              + trc.getDescription()
              + "\"  valid: "
              + trc.getNotBefore()
              + " - "
              + trc.getNotAfter());
      for (long ia : trc.getCoreAses()) {
        String auth = trc.getAuthoritativeAses().contains(ia) ? "  (authoritative)" : "";
        println("    core: " + ScionUtil.toStringIA(ia) + auth);
      }
      for (long ia : trc.getAuthoritativeAses()) {
        if (!trc.getCoreAses().contains(ia)) {
          println("    authoritative (non-core): " + ScionUtil.toStringIA(ia));
        }
      }
    }
    println("Found " + trcs.size() + " ISDs.");
  }

  public static List<Trc> getTrcs() {
    try {
      return new DownloadTrcs(findServer()).downloadTrcs();
    } catch (IOException e) {
      throw new RuntimeException("Error while downloading TRCs: ", e);
    }
  }

  private static String findServer() {
    String server =
        ScionUtil.getPropertyOrEnv(Constants.PROPERTY_BOOTSTRAP_HOST, Constants.ENV_BOOTSTRAP_HOST);
    if (server == null) {
      server = DNSHelper.searchForDiscoveryService();
    }
    if (server == null) {
      throw new IllegalStateException(
          "No control service found. Please specify <host>[:<port>] or set "
              + Constants.ENV_BOOTSTRAP_HOST);
    }
    return server;
  }

  /**
   * @return all ISDs known to the control service, as entries of the form ISD-0.
   */
  public List<ParseAssignments.HostEntry> downloadIsds() throws IOException {
    List<ParseAssignments.HostEntry> result = new ArrayList<>();
    for (TrcId id : downloadTrcIds()) {
      result.add(new ParseAssignments.HostEntry(((long) id.isd) << 48, ""));
    }
    return result;
  }

  /**
   * @return the latest TRC of every ISD known to the control service, sorted by ISD.
   */
  public List<Trc> downloadTrcs() throws IOException {
    List<Trc> result = new ArrayList<>();
    for (TrcId id : downloadTrcIds()) {
      String path = "trcs/isd" + id.isd + "-b" + id.base + "-s" + id.serial;
      TrcJson json = gson.fromJson(fetch(path), TrcJson.class);
      result.add(new Trc(id, json));
    }
    return result;
  }

  /**
   * @return the ID of the latest TRC of every ISD known to the CS, sorted by ISD.
   */
  List<TrcId> downloadTrcIds() throws IOException {
    TrcEntry[] entries = gson.fromJson(fetch("trcs"), TrcEntry[].class);
    // The CS may return several TRCs per ISD; keep only the latest one.
    Map<Integer, TrcId> latest = new TreeMap<>();
    for (TrcEntry e : entries) {
      TrcId id = e.id;
      TrcId prev = latest.get(id.isd);
      if (prev == null
          || id.base > prev.base
          || (id.base == prev.base && id.serial > prev.serial)) {
        latest.put(id.isd, id);
      }
    }
    return new ArrayList<>(latest.values());
  }

  private String fetch(String resource) throws IOException {
    return Jsoup.connect("http://" + server + "/" + resource)
        .ignoreContentType(true)
        .timeout(TIMEOUT_MS)
        .execute()
        .body();
  }

  public static class Trc {
    private final int isd;
    private final int base;
    private final int serial;
    private final String description;
    private final String notBefore;
    private final String notAfter;
    private final List<Long> coreAses = new ArrayList<>();
    private final List<Long> authoritativeAses = new ArrayList<>();

    private Trc(TrcId id, TrcJson json) {
      this.isd = id.isd;
      this.base = id.base;
      this.serial = id.serial;
      this.description = json.description;
      this.notBefore = json.validity == null ? null : json.validity.notBefore;
      this.notAfter = json.validity == null ? null : json.validity.notAfter;
      if (json.coreAses != null) {
        json.coreAses.forEach(s -> coreAses.add(parseIsdAs(isd, s)));
      }
      if (json.authoritativeAses != null) {
        json.authoritativeAses.forEach(s -> authoritativeAses.add(parseIsdAs(isd, s)));
      }
    }

    /** Parses either "ISD-AS" or a plain "AS" (which is then combined with the TRC's ISD). */
    private static long parseIsdAs(int isd, String s) {
      if (s.contains("-")) {
        return ScionUtil.parseIA(s);
      }
      return ScionUtil.parseIA(isd + "-" + s);
    }

    public int getIsd() {
      return isd;
    }

    public int getBase() {
      return base;
    }

    public int getSerial() {
      return serial;
    }

    public String getDescription() {
      return description;
    }

    public String getNotBefore() {
      return notBefore;
    }

    public String getNotAfter() {
      return notAfter;
    }

    public List<Long> getCoreAses() {
      return coreAses;
    }

    public List<Long> getAuthoritativeAses() {
      return authoritativeAses;
    }
  }

  private static class TrcEntry {
    TrcId id;
  }

  static class TrcId {
    int isd;

    @SerializedName("base_number")
    int base;

    @SerializedName("serial_number")
    int serial;
  }

  private static class TrcJson {
    String description;

    @SerializedName("core_ases")
    List<String> coreAses;

    @SerializedName("authoritative_ases")
    List<String> authoritativeAses;

    Validity validity;
  }

  private static class Validity {
    @SerializedName("not_before")
    String notBefore;

    @SerializedName("not_after")
    String notAfter;
  }
}
