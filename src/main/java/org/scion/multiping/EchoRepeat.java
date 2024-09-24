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
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.scion.jpan.*;
import org.scion.jpan.internal.PathRawParser;
import org.scion.multiping.util.*;
import org.scion.multiping.util.Record;

/**
 * This program takes a list of ISD/AS addresses and tries to measure latency to each of them vi
 * traceroute. It can also attempt an ICMP ping for comparison.<br>
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
public class EchoRepeat {
    private static final String FILE_CONFIG = "EchoRepeatConfig.json";

    private final int localPort;

    private int nPingTried = 0;
    private int nPingSuccess = 0;
    private int nPingTimeout = 0;
    private int nPingError = 0;

    private static Config config;
    private static FileWriter fileWriter;

    private static final boolean SHOW_PATH = true;

    public EchoRepeat(int localPort) {
        this.localPort = localPort;
    }

    public static void main(String[] args) throws IOException {
        // System.setProperty(Constants.PROPERTY_DNS_SEARCH_DOMAINS, "ethz.ch.");

        config = Config.read(FILE_CONFIG);
        PRINT = config.consoleOutput;

        // Output: ISD/AS, remote IP, time, hopCount, path, [pings]
        fileWriter = new FileWriter(config.outputFile);

        // Local port must be 30041 for networks that expect a dispatcher
        EchoRepeat demo = new EchoRepeat(config.localPort);
        List<ParseAssignments.HostEntry> list = ParseAssignments.getList(config.isdAsFile);
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
        InetSocketAddress dstIP;
        if (remote.getIP() == null) {
            dstIP = new InetSocketAddress(InetAddress.getByAddress(new byte[]{1, 2, 3, 4}), 12345);
        } else {
            dstIP = new InetSocketAddress(remote.getIP(), 30041);
        }
        int nPaths;
        Record rec;
        Ref<Record.Attempt> bestAttempt = Ref.empty();
        try {
            List<Path> paths = service.getPaths(remote.getIsdAs(), dstIP);
            if (paths.isEmpty()) {
                String src = ScionUtil.toStringIA(service.getLocalIsdAs());
                String dst = ScionUtil.toStringIA(remote.getIsdAs());
                println("WARNING: No path found from " + src + " to " + dst);
                Record.createNoPathRecord(remote.getIsdAs(), fileWriter);
                return;
            }
            nPaths = paths.size();
            rec = measureLatency(paths, bestAttempt);
        } catch (ScionRuntimeException e) {
            println("ERROR: " + e.getMessage());
            Record.createErrorRecord(remote.getIsdAs(), fileWriter);
            return;
        }

        if (rec == null) {
            return;
        }

        // ICMP ping
        String icmpMs = ICMP.pingICMP(rec.getPath().getRemoteAddress(), config);
        rec.setICMP(icmpMs);

        // output
        int nHops = PathRawParser.create(rec.getPath().getRawPath()).getHopCount();
        String out = rec.getRemoteIP() + "  nPaths=" + nPaths + "  nHops=" + nHops;
        out += "  time=" + bestAttempt.get().getPingMs() + "ms" + "  ICMP=" + icmpMs;
        if (SHOW_PATH) {
            out += "  " + ScionUtil.toStringPath(rec.getPath().getMetadata());
        }
        println(out);
    }

    private Record measureLatency(List<Path> paths, Ref<Record.Attempt> refBest) {
        ByteBuffer empty = ByteBuffer.allocate(0);

        // Create list of required paths/records
        int maxPath = Math.min(paths.size(), config.maxPathsPerDestination);
        List<Record> recordList = initializeRecords(paths, maxPath);
        if (recordList == null) {
            return null;
        }

        Record best = null;
        double currentBestMs = Double.MAX_VALUE;
        ResponseHandler handler = new ResponseHandler();
        try (ScmpSenderAsync sender =
                     Scmp.newSenderAsyncBuilder(handler).setLocalPort(localPort).build()) {
            for (int attemptCount = 0; attemptCount < config.attemptRepeatCnt; attemptCount++) {
                Instant start = Instant.now();
                Map<Integer, Record> seqToPathMap = new HashMap<>();

                // Send
                for (Record rec : recordList) {
                    nPingTried++;
                    int sequenceID;
                    if (rec.getRemoteIP() == null) {
                        sequenceID = sender.sendTracerouteLast(rec.getPath());
                    } else {
                        sequenceID = sender.sendEcho(rec.getPath(), empty);
                    }
                    if (sequenceID < 0) {
                        rec.registerAttempt(Record.Attempt.State.ERROR_SEQID);
                        throw new IllegalStateException();
                    }
                    seqToPathMap.put(sequenceID, rec);
                }

                // Wait
                while (handler.messages.size() + handler.errors.size() < maxPath) {
                    // TODO use notify/wait instead.
                    sleep(50);
                }

                // Receive
                while (!handler.messages.isEmpty()) {
                    Scmp.TimedMessage msg = handler.messages.remove();
                    Record rec = seqToPathMap.get(msg.getSequenceNumber());
                    if (rec == null) {
                        System.out.println("ERROR: SeqID not found: " + msg.getSequenceNumber());
                        if (msg.isTimedOut()) {
                            nPingTimeout++;
                        } else {
                            nPingError++;
                        }
                        continue;
                    }
                    Record.Attempt attempt = rec.registerAttempt(msg);
                    if (msg.isTimedOut()) {
                        nPingTimeout++;
                    } else {
                        nPingSuccess++;
                    }

                    if (best == null || attempt.getPingMs() < currentBestMs) {
                        currentBestMs = attempt.getPingMs();
                        refBest.set(attempt);
                        best = rec;
                    }
                }

                // TODO errors
                while (!handler.errors.isEmpty()) {
                    nPingError++;
                    handler.errors.remove(); // TODO use it
                }

                seqToPathMap.clear();

                long usedMillis = Instant.now().toEpochMilli() - start.toEpochMilli();
                if (usedMillis < config.attemptDelayMs) {
                    sleep(config.attemptDelayMs - usedMillis);
                }
            }

            for (Record rec : recordList) {
                rec.finishMeasurement(fileWriter);
            }

            return best;
        } catch (IOException e) {
            println("ERROR: " + e.getMessage());
            nPingError++;
            return null;
        }
    }

    private static List<Record> initializeRecords(List<Path> paths, int maxPath) {
        List<Record> recordList = new ArrayList<>();
        for (int pathId = 0; pathId < maxPath; pathId++) {
            Path path = paths.get(pathId);
            Record rec = Record.startMeasurement(path);
            if (path.getRawPath().length == 0) {
                println(" -> local AS, no timing available");
                rec.setState(Record.State.LOCAL_AS);
                rec.finishMeasurement(fileWriter);
                return null;
            }
            recordList.add(rec);
        }
        return recordList;
    }

    private static class ResponseHandler implements ScmpSenderAsync.ResponseHandler {
        final Queue<Scmp.TimedMessage> messages = new ConcurrentLinkedQueue<>();
        final Queue<Scmp.ErrorMessage> errors = new ConcurrentLinkedQueue<>();

        @Override
        public void onResponse(Scmp.TimedMessage msg) {
            messages.add(msg);
        }

        @Override
        public void onTimeout(Scmp.TimedMessage msg) {
            messages.add(msg);
        }

        @Override
        public void onError(Scmp.ErrorMessage msg) {
            errors.add(msg);
        }
    }
}
