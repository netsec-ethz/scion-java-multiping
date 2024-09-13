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

import static org.scion.multiping.util.Util.*;

import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.scion.jpan.*;
import org.scion.jpan.internal.PathRawParser;
import org.scion.multiping.util.*;
import org.scion.multiping.util.Record;

/**
 * This program takes a list of ISD/AS addresses and tries to measure latency to each of them vi traceroute. It
 * can also attempt an ICMP ping for comparison.<br>
 * The list is imported from a file.
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

  private final int localPort;

  private int nPingTried = 0;
  private int nPingSuccess = 0;
  private int nPingTimeout = 0;
  private int nPingError = 0;

  private static Config config;
  private static final List<Result> results = new ArrayList<>();
  private static final List<Record> records = new ArrayList<>();
  private static FileWriter fileWriter;

  private static final boolean SHOW_PATH = true;

  public EchoAsync(int localPort) {
    this.localPort = localPort;
  }

  public static void main(String[] args) throws IOException {
    PRINT = true;
    System.setProperty(Constants.PROPERTY_DNS_SEARCH_DOMAINS, "ethz.ch.");

    config = Config.read(FILE_CONFIG);
    // Output: ISD/AS, remote IP, time, hopCount, path, [pings]
    fileWriter = new FileWriter(FILE_OUTPUT);

    // Local port must be 30041 for networks that expect a dispatcher
    EchoAsync demo = new EchoAsync(30041);
    List<ParseAssignments.HostEntry> list = ParseAssignments.getList(FILE_INPUT);
    for (int i = 0; i < config.roundRepeatCnt; i++) {
      Instant start = Instant.now();
      for (ParseAssignments.HostEntry e : list) {
        print(ScionUtil.toStringIA(e.getIsdAs()) + " " + e.getName() + "  ");
        demo.runRepeat(e);
      }
      long usedMillis = Instant.now().toEpochMilli() - start.toEpochMilli();
      if (usedMillis < config.roundDelaySec * 1000L) {
        sleep(config.roundDelaySec * 1000L - usedMillis);
      }
    }
    fileWriter.close();

    // max:
    Result maxPing =
        results.stream().max((o1, o2) -> (int) (o1.getPingMs() - o2.getPingMs())).get();
    Result maxHops = results.stream().max((o1, o2) -> o1.getHopCount() - o2.getHopCount()).get();
    Result maxPaths = results.stream().max((o1, o2) -> o1.getPathCount() - o2.getPathCount()).get();

    println("");
    println("Max hops  = " + maxHops.getHopCount() + ":    " + maxHops);
    println("Max ping  = " + round(maxPing.getPingMs(), 2) + "ms:    " + maxPing);
    println("Max paths = " + maxPaths.getPathCount() + ":    " + maxPaths);

    println("");
    println("Ping Stats:");
    println(" all        = " + demo.nPingTried);
    println(" success    = " + demo.nPingSuccess);
    println(" timeout    = " + demo.nPingTimeout);
    println(" error      = " + demo.nPingError);
    println("ICMP Stats:");
    println(" all        = " + ICMP.nIcmpTried);
    println(" success    = " + ICMP.nIcmpSuccess);
    println(" timeout    = " + ICMP.nIcmpTimeout);
    println(" error      = " + ICMP.nIcmpError);
  }

  private void runRepeat(ParseAssignments.HostEntry remote) throws IOException {
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
        results.add(new Result(remote, Result.State.NO_PATH));
        return;
      }
      nPaths = paths.size();
      msg = measureLatency(paths, bestPath);
    } catch (ScionRuntimeException e) {
      println("ERROR: " + e.getMessage());
      results.add(new Result(remote, Result.State.ERROR));
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
  }

  private Scmp.TracerouteMessage measureLatency(List<Path> paths, Ref<Path> refBest) {
    Queue<Scmp.TracerouteMessage> messages = new ConcurrentLinkedQueue<>();
    Queue<Scmp.ErrorMessage> errors = new ConcurrentLinkedQueue<>();

    ScmpSender.ScmpResponseHandler handler =
        new ScmpSender.ScmpResponseHandler() {
          @Override
          public void onResponse(Scmp.TimedMessage msg) {
            messages.add((Scmp.TracerouteMessage) msg);
          }

          @Override
          public void onTimeout(Scmp.TimedMessage msg) {
            messages.add((Scmp.TracerouteMessage) msg);
          }

          @Override
          public void onError(Scmp.ErrorMessage msg) {
            errors.add(msg);
          }
        };

    // Create list of required paths/records
    int maxPath = Math.min(paths.size(), config.maxPathsPerDestination);
    List<Record> recordList = new ArrayList<>();
    for (int pathId = 0; pathId < maxPath; pathId++) {
      Path path = paths.get(pathId);
      Record rec = Record.startMeasurement(path);
      records.add(rec);
      if (path.getRawPath().length == 0) {
        println(" -> local AS, no timing available");
        rec.finishMeasurement(fileWriter);
        return null;
      }
      recordList.add(rec);
    }

    Scmp.TracerouteMessage best = null;
    try (ScmpSender scmpChannel = Scmp.createSender(handler, localPort)) {
      for (int attempt = 0; attempt < config.attemptRepeatCnt; attempt++) {
        Instant start = Instant.now();
        Map<Integer, Record> seqToPathMap = new HashMap<>();

        // Send
        for (Record rec: recordList) {
          nPingTried++;
          int sequenceID = scmpChannel.asyncTracerouteLast(rec.getPath());
          if (sequenceID < 0) {
            throw new IllegalStateException();
          }
          seqToPathMap.put(sequenceID, rec);
        }

        // Wait
        while (messages.size() + errors.size() < maxPath) {
          sleep(50);
        }

        // Receive
        while (!messages.isEmpty()) {
          Scmp.TracerouteMessage msg = messages.remove();
          Record rec = seqToPathMap.get(msg.getSequenceNumber());
          rec.registerAttempt(msg);
          if (msg.isTimedOut()) {
            nPingTimeout++;
          } else {
            nPingSuccess++;
          }

          if (best == null || msg.getNanoSeconds() < best.getNanoSeconds()) {
            best = msg;
            // In theory the request path should be the same as the response path.
            refBest.set(msg.getRequest().getPath());
          }
        }

        // TODO errors
        while (!errors.isEmpty()) {
          nPingError++;
          errors.remove(); // TODO use it
        }

        seqToPathMap.clear();

        long usedMillis = Instant.now().toEpochMilli() - start.toEpochMilli();
        if (usedMillis < config.attemptDelayMs) {
          sleep(config.attemptDelayMs - usedMillis);
        }
      }

      for (Record rec: recordList) {
        rec.finishMeasurement(fileWriter);
      }

      return best;
    } catch (IOException e) {
      println("ERROR: " + e.getMessage());
      nPingError++;
      return null;
    }
  }
}
