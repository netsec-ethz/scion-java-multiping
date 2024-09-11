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

package org.scion.multiping;

import org.scion.jpan.*;
import org.scion.jpan.internal.PathRawParser;
import org.scion.multiping.util.Config;
import org.scion.multiping.util.ICMP;
import org.scion.multiping.util.ParseAssignments;
import org.scion.multiping.util.Result;

import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.scion.multiping.util.Util.*;

/**
 * This program takes a list of ISD/AS addresses and tries to measure latency to all of them. It
 * will also attempt an ICMP ping for comparison.<br>
 * The list is derived from <a
 * href="https://docs.anapaya.net/en/latest/resources/isd-as-assignments/">here</a> and is locally
 * stored in ISD-AS-Assignments.csv, see {@link ParseAssignments}.java.
 *
 * <p>There are several options for executing measurements (see "Policy"):<br>
 * - SCMP traceroute vs SCMP echo<br>
 * - Fastest vs shortest
 *
 * <p>Shortest: Report results on the path with the fewest hops. The number of hops can be evaluated
 * locally, so this is very fast.
 *
 * <p>Fastest: Report the path with the lowest latency. This takes much longer because it will try
 * all available paths before it can report on the best path.
 */
public class EchoAsync {
  private static final String FILE_CONFIG = "EchoRepeatConfig.json";
  private static final String FILE_INPUT = "EchoRepeatDestinations-short.csv";
  private static final String FILE_OUTPUT = "EchoRepeatOutput.csv";

  private static final boolean PRINT = true;
  private final int localPort;

  private int nAsTried = 0;
  private int nAsSuccess = 0;
  private int nAsError = 0;
  private int nAsTimeout = 0;
  private int nAsNoPathFound = 0;

  private int nPathTried = 0;
  private int nPathSuccess = 0;
  private int nPathTimeout = 0;

  private static Config config;
  private static final List<Result> results = new ArrayList<>();
  private static final List<Record> records = new ArrayList<>();
  private static FileWriter fileWriter;

  private enum Policy {
    /** Fastest path using SCMP traceroute */
    FASTEST_TR,
    /** Shortest path using SCMP traceroute */
    SHORTEST_TR,
    /** Fastest path using SCMP traceroute, but skip on-route echos */
    FASTEST_TR_LAST,
  }

  private static final Policy POLICY = Policy.FASTEST_TR;
  private static final boolean SHOW_PATH = true;

  public EchoAsync(int localPort) {
    this.localPort = localPort;
  }

  public static void main(String[] args) throws IOException {
    System.setProperty(Constants.PROPERTY_DNS_SEARCH_DOMAINS, "ethz.ch.");

    config = Config.read(FILE_CONFIG);
    // Output: ISD/AS, remote IP, time, hopCount, path, [pings]
    fileWriter = new FileWriter(FILE_OUTPUT);

    // Local port must be 30041 for networks that expect a dispatcher
    EchoAsync demo = new EchoAsync(30041);
    List<ParseAssignments.HostEntry> list = ParseAssignments.getList(FILE_INPUT);
    // List<ParseAssignments.HostEntry> list = DownloadAssignments.getList();
    for (int i = 0; i < config.roundRepeatCnt; i++) {
      Instant start = Instant.now();
      for (ParseAssignments.HostEntry e : list) {
        print(ScionUtil.toStringIA(e.getIsdAs()) + " " + e.getName() + "  ");
        demo.runDemo(e);
      }
      long usedMillis = Instant.now().toEpochMilli() - start.toEpochMilli();
      if (usedMillis < config.roundDelaySec * 1000L) {
        sleep(config.roundDelaySec * 1000L - usedMillis);
      }
    }
    fileWriter.close();

    // max:
    org.scion.multiping.util.Result maxPing = results.stream().max((o1, o2) -> (int) (o1.getPingMs() - o2.getPingMs())).get();
    org.scion.multiping.util.Result maxHops = results.stream().max((o1, o2) -> o1.getHopCount() - o2.getHopCount()).get();
    org.scion.multiping.util.Result maxPaths = results.stream().max((o1, o2) -> o1.getPathCount() - o2.getPathCount()).get();

    println("");
    println("Max hops  = " + maxHops.getHopCount() + ":    " + maxHops);
    println("Max ping  = " + round(maxPing.getPingMs(), 2) + "ms:    " + maxPing);
    println("Max paths = " + maxPaths.getPathCount() + ":    " + maxPaths);

    println("");
    println("AS Stats:");
    println(" all        = " + demo.nAsTried);
    println(" success    = " + demo.nAsSuccess);
    println(" no path    = " + demo.nAsNoPathFound);
    println(" timeout    = " + demo.nAsTimeout);
    println(" error      = " + demo.nAsError);
    println("Path Stats:");
    println(" all        = " + demo.nPathTried);
    println(" success    = " + demo.nPathSuccess);
    println(" timeout    = " + demo.nPathTimeout);
    println("ICMP Stats:");
    println(" all        = " + ICMP.nIcmpTried);
    println(" success    = " + ICMP.nIcmpSuccess);
    println(" timeout    = " + ICMP.nIcmpTimeout);
    println(" error      = " + ICMP.nIcmpError);
  }

  private void runDemo(ParseAssignments.HostEntry remote) throws IOException {
    nAsTried++;
    ScionService service = Scion.defaultService();
    // Dummy address. The traceroute will contact the control service IP instead.
    InetSocketAddress destinationAddress =
        new InetSocketAddress(InetAddress.getByAddress(new byte[] {1, 2, 3, 4}), 12345);
    int nPaths;
    Scmp.TimedMessage msg;
    Ref<Path> bestPath = Ref.empty();
    try {
      List<Path> paths = service.getPaths(remote.getIsdAs(), destinationAddress);
      if (paths.isEmpty()) {
        String src = ScionUtil.toStringIA(service.getLocalIsdAs());
        String dst = ScionUtil.toStringIA(remote.getIsdAs());
        println("WARNING: No path found from " + src + " to " + dst);
        nAsNoPathFound++;
        results.add(new Result(remote, Result.ResultState.NO_PATH));
        return;
      }
      nPaths = paths.size();
      msg = findPaths(paths, bestPath);
    } catch (ScionRuntimeException e) {
      println("ERROR: " + e.getMessage());
      nAsError++;
      results.add(new Result(remote, Result.ResultState.ERROR));
      return;
    }
    Result result = new Result(remote, msg, bestPath.get(), nPaths);
    results.add(result);

    if (msg == null) {
      return;
    }

    // ICMP ping
    String icmpMs = ICMP.pingICMP(msg.getPath().getRemoteAddress(), config);
    result.setICMP(icmpMs);

    // output
    double millis = round(msg.getNanoSeconds() / (double) 1_000_000, 2);
    int nHops = PathRawParser.create(msg.getPath().getRawPath()).getHopCount();
    String addr = msg.getPath().getRemoteAddress().getHostAddress();
    String out = addr + "  nPaths=" + nPaths + "  nHops=" + nHops;
    out += "  time=" + millis + "ms" + "  ICMP=" + icmpMs;
    if (SHOW_PATH) {
      out += "  " + ScionUtil.toStringPath(bestPath.get().getMetadata());
    }
    println(out);
    if (msg.isTimedOut()) {
      nAsTimeout++;
    } else {
      nAsSuccess++;
    }
  }

  private Scmp.TimedMessage findPaths(List<Path> paths, Ref<Path> bestOut) {
    switch (POLICY) {
      case FASTEST_TR:
        return findFastestTR(paths, bestOut);
      case SHORTEST_TR:
        return findShortestTR(paths, bestOut);
      case FASTEST_TR_LAST:
        return findFastestTR_Last(paths, bestOut);
      default:
        throw new UnsupportedOperationException();
    }
  }

  private Scmp.TracerouteMessage findShortestTR(List<Path> paths, Ref<Path> refBest) {
    Path path = PathPolicy.MIN_HOPS.filter(paths);
    refBest.set(path);
    try (ScmpChannel2 scmpChannel = Scmp.createAsyncChannel(localPort)) {
      nPathTried++;
      List<Scmp.TracerouteMessage> messages = scmpChannel.sendTracerouteRequest(path);
      if (messages.isEmpty()) {
        println(" -> local AS, no timing available");
        nPathSuccess++;
        nAsSuccess++;
        return null;
      }

      Scmp.TracerouteMessage msg = messages.get(messages.size() - 1);
      if (msg.isTimedOut()) {
        nPathTimeout++;
        return msg;
      }

      nPathSuccess++;
      return msg;
    } catch (IOException e) {
      println("ERROR: " + e.getMessage());
      nAsError++;
      return null;
    }
  }

  private Scmp.TracerouteMessage findFastestTR(List<Path> paths, Ref<Path> refBest) {
    Scmp.TracerouteMessage best = null;
    try (ScmpChannel2 scmpChannel = Scmp.createAsyncChannel(localPort)) {
      for (int i = 0; i < paths.size() && i < config.maxPathsPerDestination; i++) {
        Path path = paths.get(i);
        nPathTried++;
        Record rec = Record.startMeasurement(path);
        for (int attempt = 0; attempt < config.attemptRepeatCnt; attempt++) {
          Instant start = Instant.now();
          List<Scmp.TracerouteMessage> messages = scmpChannel.sendTracerouteRequest(path);
          if (messages.isEmpty()) {
            println(" -> local AS, no timing available");
            nPathSuccess++;
            nAsSuccess++;
            rec.finishMeasurement();
            return null;
          }

          Scmp.TracerouteMessage msg = messages.get(messages.size() - 1);
          rec.registerAttempt(msg);
          if (msg.isTimedOut()) {
            nPathTimeout++;
            return msg;
          }

          nPathSuccess++;

          if (best == null || msg.getNanoSeconds() < best.getNanoSeconds()) {
            best = msg;
            refBest.set(path);
          }
          long usedMillis = Instant.now().toEpochMilli() - start.toEpochMilli();
          if (usedMillis < config.attemptDelayMs) {
            sleep(config.attemptDelayMs - usedMillis);
          }
        }
        rec.finishMeasurement();
      }
      return best;
    } catch (IOException e) {
      println("ERROR: " + e.getMessage());
      nAsError++;
      return null;
    }
  }

  private Scmp.TracerouteMessage findFastestTR_Last(List<Path> paths, Ref<Path> refBest) {
    Scmp.TracerouteMessage best = null;
    try (ScmpChannel2 scmpChannel = Scmp.createAsyncChannel(localPort)) {
      for (int i = 0; i < paths.size() && i < config.maxPathsPerDestination; i++) {
        Path path = paths.get(i);
        nPathTried++;
        Record rec = Record.startMeasurement(path);
        for (int attempt = 0; attempt < config.attemptRepeatCnt; attempt++) {
          Instant start = Instant.now();
          List<Scmp.TracerouteMessage> messages = scmpChannel.sendTracerouteRequest(path);
          if (messages.isEmpty()) {
            println(" -> local AS, no timing available");
            nPathSuccess++;
            nAsSuccess++;
            rec.finishMeasurement();
            return null;
          }

          Scmp.TracerouteMessage msg = messages.get(messages.size() - 1);
          rec.registerAttempt(msg);
          if (msg.isTimedOut()) {
            nPathTimeout++;
            return msg;
          }

          nPathSuccess++;

          if (best == null || msg.getNanoSeconds() < best.getNanoSeconds()) {
            best = msg;
            refBest.set(path);
          }
          long usedMillis = Instant.now().toEpochMilli() - start.toEpochMilli();
          if (usedMillis < config.attemptDelayMs) {
            sleep(config.attemptDelayMs - usedMillis);
          }
        }
        rec.finishMeasurement();
      }
      return best;
    } catch (IOException e) {
      println("ERROR: " + e.getMessage());
      nAsError++;
      return null;
    }
  }

  private static class Record {
    private final long isdAs;
    private final ArrayList<Attempt> attempts = new ArrayList<>();
    private final Instant time;
    private final Path path;
    private String remoteIP;
    private String icmp;

    public Record(Instant time, Path request) {
      this.isdAs = request.getRemoteIsdAs();
      this.time = time;
      this.path = request;
    }

    public static Record startMeasurement(Path path) {
      Record r = new Record(Instant.now(), path);
      records.add(r);
      return r;
    }

    public void registerAttempt(Scmp.TimedMessage msg) {
      Attempt a = new Attempt(msg);
      if (remoteIP == null && a.state != Result.ResultState.LOCAL_AS) {
        remoteIP = msg.getPath().getRemoteAddress().getHostAddress();
      }
      attempts.add(a);
    }

    public void finishMeasurement() {
      int nHops = PathRawParser.create(path.getRawPath()).getHopCount();
      StringBuilder out = new StringBuilder(ScionUtil.toStringIA(isdAs));
      out.append(",").append(remoteIP);
      out.append(",").append(time);
      out.append(",").append(nHops);
      out.append(",").append(ScionUtil.toStringPath(path.getRawPath()));
      for (Attempt a : attempts) {
        out.append(",").append(round(a.pingMs, 2));
      }
      out.append(System.lineSeparator());
      try {
        fileWriter.append(out.toString());
        fileWriter.flush();
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }

    public long getIsdAs() {
      return isdAs;
    }

    public void setICMP(String icmp) {
      this.icmp = icmp;
    }

    @Override
    public String toString() {
      StringBuilder out = new StringBuilder(ScionUtil.toStringIA(isdAs));
      out.append("   ").append(ScionUtil.toStringPath(path.getMetadata()));
      out.append("  ").append(remoteIP);
      for (Attempt a : attempts) {
        out.append(a);
      }
      return out + "  ICMP=" + icmp;
    }
  }

  private static class Attempt {
    private double pingMs;
    private Result.ResultState state = Result.ResultState.NOT_DONE;

    Attempt(Scmp.TimedMessage msg) {
      if (msg == null) {
        state = Result.ResultState.LOCAL_AS;
        return;
      }
      if (msg.isTimedOut()) {
        state = Result.ResultState.TIME_OUT;
      } else {
        pingMs = msg.getNanoSeconds() / (double) 1_000_000;
        state = Result.ResultState.DONE;
      }
    }

    @Override
    public String toString() {
      return "  time=" + round(pingMs, 2) + "ms";
    }
  }

  private static class Ref<T> {
    public T t;

    private Ref(T t) {
      this.t = t;
    }

    public static <T> Ref<T> empty() {
      return new Ref<>(null);
    }

    public static <T> Ref<T> of(T t) {
      return new Ref<>(t);
    }

    public T get() {
      return t;
    }

    public void set(T t) {
      this.t = t;
    }
  }
}
