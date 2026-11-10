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

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.scion.jpan.*;
import org.scion.jpan.internal.Shim;
import org.scion.multiping.util.*;

/**
 * This program takes a list of ISD/AS addresses and tries to measure latency to all of them.
 * The list is derived from <a
 * href="https://docs.anapaya.net/en/latest/resources/isd-as-assignments/">here</a> and is locally
 * stored in ISD-AS-Assignments.csv, see {@link ParseAssignments}.java.
 *
 * <p>Measurements are done with SCMP traceroute
 */
public class TracerouteAll {
  // Traceroute interval milliseconds
  private static final int TR_INTERVAL_MS = 5;
  private static final boolean RUN_TIMEOUT_ANALYZER = false;
  private static final Config config = new Config();

  private static double maxPPS = 0;

  static int localPort = 30041;
  private static boolean startShim = false;

  static {
    config.tryICMP = false;
    config.attemptRepeatCnt = 3;
  }

  private final Set<Long> listedAs = new HashSet<>();
  private final Set<Long> seenAs = new HashSet<>();
  private final ResultSummary summary = new ResultSummary();

  private final ScionProvider service;
  private final List<Path> timeouts = new ArrayList<>();
  private static final boolean SHOW_PATH = false;

  TracerouteAll(ScionProvider service) {
    this.service = service;
  }

  public static void main(String[] argsArray) throws IOException {
    PRINT = true;

    // Initialize port from config, but CLI overrides config file.
    localPort = config.getLocalPortOr30041();
    parseArgs(argsArray);
    // System.setProperty(Constants.PROPERTY_BOOTSTRAP_HOST, "31.130.239.184");
    // System.setProperty(Constants.PROPERTY_BOOTSTRAP_PATH_SERVICE, "192.168.53.19:48080");
    System.setProperty(Constants.PROPERTY_SHIM, Boolean.toString(startShim)); // disable SHIM

    println("Settings:");
    println("  Local port=" + localPort);
    println("  JPAN SHIM active=" + Shim.isInstalled());

    long t1 = System.currentTimeMillis();
    TracerouteAll tracerouteAll = new TracerouteAll(ScionProvider.defaultProvider(localPort));
    tracerouteAll.run();
    tracerouteAll.summary.prettyPrint(config);
    long t2 = System.currentTimeMillis();
    println("Max PPS:    " + maxPPS);
    println("Total time: " + round((t2 - t1) / 1000.0, 2) + "s");

    if (RUN_TIMEOUT_ANALYZER) {
      println();
      TimeoutAnalyzer.analyze(tracerouteAll.timeouts);
    }
  }

  private static void parseArgs(String[] argsArray) {
    List<String> args = new ArrayList<>(Arrays.asList(argsArray));
    while (!args.isEmpty()) {
      switch (args.get(0)) {
        case "--help":
          Main.printUsagePingAll();
          System.exit(0);
          break;
        case "--shim":
          startShim = true;
          break;
        case "--port":
          if (args.size() < 2) {
            Util.println("Error: --port requires a port number");
            Main.printUsagePingAll();
            System.exit(1);
          }
          try {
            localPort = Integer.parseInt(args.get(1));
          } catch (NumberFormatException e) {
            Util.println("Error: Invalid port number: " + args.get(1));
            Main.printUsagePingAll();
            System.exit(1);
          }
          args.remove(1);
          break;
        default:
          Util.println("Unknown option: " + args.get(0));
          Main.printUsagePingAll();
          System.exit(1);
      }
      args.remove(0);
    }
  }

  ResultSummary run() throws IOException {
    List<ParseAssignments.HostEntry> allASes = service.getIsdAsEntries();
    // remove entry for local AS
    List<Long> localASes = service.getLocalIsdAsList();
    allASes =
            allASes.stream()
                    .filter(e -> !localASes.contains(e.getIsdAs()))
                    .collect(Collectors.toList());
    // Process all ASes
    for (ParseAssignments.HostEntry e : allASes) {
      print(ScionUtil.toStringIA(e.getIsdAs()) + "\t \"" + e.getName() + "\"\t  ");
      //      if (e.getIsdAs() != ScionUtil.parseIA("64-559")) {
      //        continue;
      //      }
      runAS(e);
      listedAs.add(e.getIsdAs());
    }

    // Try to identify ASes that occur in any paths but that are not on the public list.
    for (Long isdAs : seenAs) {
      if (!listedAs.contains(isdAs)) {
        summary.incSeenButNotListed();
      }
    }
    return summary;
  }

  private void runAS(ParseAssignments.HostEntry remote) throws IOException {
    summary.incIsdAsTried(remote.getIsdAs());
    // Dummy address. The traceroute will contact the control service IP instead.
    InetAddress dummyIP = InetAddress.getByAddress(new byte[]{0, 0, 0, 0});
    ScionSocketAddress destinationAddress =
            ScionSocketAddress.from(remote.getIsdAs(), dummyIP, 30041);
    Result result;
    try {
      List<Path> paths = service.getPaths(remote.getIsdAs(), destinationAddress);
      if (paths.isEmpty()) {
        String src = ScionUtil.toStringIAs(service.getLocalIsdAsList());
        String dst = ScionUtil.toStringIA(remote.getIsdAs());
        println("WARNING: No path found from [" + src + "] to " + dst);
        summary.incAsNoPathFound(remote.getIsdAs());
        summary.add(Result.createNoPath(remote));
        return;
      }
      summary.checkTotalMax(remote.getIsdAs(), paths.size());
      summary.incUniquePathTried(paths.size());
      result = Result.createUnfinished(remote, paths.size(), config.attemptRepeatCnt);
      for (int i = 0; i < config.attemptRepeatCnt; i++) {
        findPaths(paths, result, summary, i);
      }
      result.finish();
      summary.add(result);
    } catch (ScionRuntimeException e) {
      printlnERROR(e.getMessage());
      summary.incAsError(remote.getIsdAs());
      summary.add(Result.createError(remote));
      return;
    }

    if (result.isError()) {
      clearPrintQueue();
      summary.incAsError(result.getIsdAs());
      return;
    }

    // output
    print(result.getRemoteIP() + "\t  nPaths=" + result.getPathCount());
    print("\t  nHops=" + result.getHopCount() + "\t  time=");
    for (Scmp.TimedMessage m : result.getBestMessages()) {
      if (m == null) {
        print("N/A ");
        continue;
      }
      double millis = round(m.getNanoSeconds() / (double) 1_000_000, 2);
      print(millis + "ms ");
    }
    if (SHOW_PATH && result.isSuccess()) {
      print("  " + ScionUtil.toStringPath(result.getBestPath().getMetadata()));
    }
    println();
    if (result.isTimedOut()) {
      summary.incAsTimeout(remote.getIsdAs());
    } else {
      summary.incAsSuccess(remote.getIsdAs());
    }
  }

  private void findPaths(List<Path> paths, Result result, ResultSummary summary, int round) {
    PingResponseHandler handler = new PingResponseHandler(paths.size());

    // Send all requests
    try (ScionProvider.Async sender = service.getAsync(handler)) {
      long t0 = System.nanoTime();
      for (Path path : paths) {
        summary.incPathTried();
        Util.sleep(TR_INTERVAL_MS);
        sender.sendTracerouteLast(path);
      }
      long t1 = System.nanoTime();
      double t = Math.max(1, t1 - t0) / 1_000_000_000.; // Avoid div by 0
      // print(" [[t=" + t + " nP=" + paths.size() + "]] ");
      maxPPS = Math.max(maxPPS, paths.size() / t);

      // Wait for all messages to be received, BEFORE closing the "sender".
      handler.await();
    } catch (IOException e) {
      printlnERROR(e.getMessage());
      return;
    }

    if (handler.hasErrors() && handler.messages.isEmpty()) {
      summary.incPathError(handler.errors.get());
      return;
    }

    for (Scmp.TimedMessage tm : handler.messages.values()) {
      Scmp.TracerouteMessage msg = (Scmp.TracerouteMessage) tm;
      summary.checkTotalMax(msg.getIsdAs(), msg);

      result.checkBestPath(msg, round);
      if (msg.isTimedOut()) {
        summary.incPathTimeout();
        timeouts.add(msg.getPath());
        continue;
      }

      seenAs.add(msg.getIsdAs());
      summary.incPathSuccess();
    }
  }

  private static class PingResponseHandler implements ScmpSenderAsync.ResponseHandler {
    private final Map<Integer, Scmp.TimedMessage> messages = new ConcurrentHashMap<>();
    private final CountDownLatch barrier;
    private final AtomicInteger errors = new AtomicInteger();
    private final int nPaths;

    private PingResponseHandler(int nPaths) {
      this.nPaths = nPaths;
      barrier = new CountDownLatch(nPaths);
    }

    @Override
    public void onResponse(Scmp.TimedMessage msg) {
      barrier.countDown();
      messages.put(msg.getSequenceNumber(), msg);
    }

    @Override
    public void onTimeout(Scmp.TimedMessage msg) {
      barrier.countDown();
      messages.put(msg.getSequenceNumber(), msg);
    }

    @Override
    public void onError(Scmp.ErrorMessage msg) {
      errors.incrementAndGet();
      barrier.countDown();
    }

    @Override
    public void onException(Throwable t) {
      errors.incrementAndGet();
      barrier.countDown();
    }

    void await() {
      try {
        if (!barrier.await(1100, TimeUnit.MILLISECONDS)) {
          throw new IllegalStateException("Missing messages: " + barrier.getCount() + "/" + nPaths);
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }

    public boolean hasErrors() {
      return errors.get() > 0;
    }
  }
}
