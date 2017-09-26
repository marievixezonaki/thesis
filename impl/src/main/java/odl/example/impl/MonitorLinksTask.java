/*
 * Copyright © 2017 M.E Xezonaki in the context of her MSc Thesis, Department of Informatics and Telecommunications, UoA.  All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v1.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v10.html
 */
package odl.example.impl;

import org.jgrapht.GraphPath;
import org.opendaylight.controller.md.sal.binding.api.DataBroker;
import org.opendaylight.controller.sal.binding.api.RpcProviderRegistry;
import org.opendaylight.yang.gen.v1.urn.opendaylight.packet.service.rev130709.PacketProcessingService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.TimerTask;

/**
 * The class implementing a task which monitors the topology's links.
 *
 */
public class MonitorLinksTask extends TimerTask{

    private DataBroker db;
    private PacketProcessingService packetProcessingService;
    private RpcProviderRegistry rpcProviderRegistry;
    private List<Long> latencies = new ArrayList<>();
    private Integer ingressPackets = 0, egressPackets = 0, ingressBits = 0, egressBits = 0;
    String sourceMac;
    volatile static boolean packetReceivedFromController = false;
    private static HashMap<String, String> nextNodeConnectors = new HashMap();
    public static boolean isFailover = false;
    private boolean linkFailure = false;
    private String videoAbsolutePath;
    private static Long lastQoEEstimationTime = 0L;

    public MonitorLinksTask(DataBroker db, RpcProviderRegistry rpcProviderRegistry, String srcMac, String videoAbsolutePath){
        this.db = db;
        this.rpcProviderRegistry = rpcProviderRegistry;
        this.sourceMac = srcMac;
        this.videoAbsolutePath = videoAbsolutePath;
    }

    @Override
    public void run() {

        double pathMOS = -1;

        // if application streamed is VoIP
        if (ExampleImpl.applicationType.equals(VoIP.getName())){
            Long delay = monitorDelay(ExampleImpl.mainGraphWalk);
            double packetLoss = monitorPacketLoss();
            System.out.println("Total delay is " + delay + " ms");
            System.out.println("Total loss is " + packetLoss + "%");
            pathMOS = VoIP.estimateQoE(delay, packetLoss);
        }
        // if application streamed is Video
        else if (ExampleImpl.applicationType.equals(Video.getName())){
            double packetLoss = monitorPacketLoss();
            int bitsReceivedCount = findBits();
            float frameRate = computeVideoFPS(videoAbsolutePath);
            float N = computeN(frameRate);
            float bitRate;
            if (frameRate != -1 && N != -1) {
                bitRate = frameRate * bitsReceivedCount / N;
            }
            else {
                bitRate = -1L;
            }
            if (bitRate != -1){
                pathMOS = Video.estimateQoE(frameRate, bitRate, packetLoss);
            }
            System.out.println("Total loss is " + packetLoss + "%");
            System.out.println("BitsReceivedCount is " + bitsReceivedCount + " bits");
            System.out.println("Frame rate is " + frameRate + " fps");
            System.out.println("N is " + N);
            System.out.println("Bitrate is " + bitRate);
        }

        System.out.println("MOS is " + pathMOS);
        if ( (pathMOS >= 0) && (pathMOS < ExampleImpl.QoEThreshold) ) {
            System.out.println("MOS is lower than the threshold.");
            if (!isFailover && PacketProcessing.videoHasStarted) {
                if (!ExampleImpl.fastFailover) {
                    ExampleImpl.changePath();
                }
            }
            else{
                System.out.println("Cannot change path although QoE low.");
            }
        }
    //    else if (pathMOS < 0){
    //        System.out.println("Something went wrong while computing MOS.");
     //   }
        System.out.println("-----------------------------------------------------------------------------------------------------");
    }

    private Long monitorDelay(GraphPath<Integer, DomainLink> path){

        if (rpcProviderRegistry != null) {
            packetProcessingService = rpcProviderRegistry.getRpcService(PacketProcessingService.class);

            LatencyMonitor latencyMonitor = new LatencyMonitor(db, this.packetProcessingService);
            List<DomainLink> linkList = path.getEdgeList();

            //find next node connector where each packet should arrive at
            findNextNodeConnector(linkList);
            for (DomainLink link : linkList) {
                if (!NetworkGraph.getInstance().getGraphLinks().contains(link.getLink())){
                    System.out.println("A link in the path is down.");
                    linkFailure = true;
                }
                if (!link.getLink().getLinkId().getValue().contains("host") && NetworkGraph.getInstance().getGraphLinks().contains(link.getLink())) {
                    Long latency = latencyMonitor.MeasureNextLink(link.getLink(), sourceMac, nextNodeConnectors.get(link.getLink().getSource().getSourceNode().getValue()));
                    while (packetReceivedFromController == false){

                    }
                    latencies.add(latency);
                }
            }
        }
        Long totalDelay = 0L;
        //compute path's total delay
        if (latencies.size() > 0){
            totalDelay = computeTotalDelay(latencies);
        }
        latencies.clear();
        return totalDelay;
    }

    private void findNextNodeConnector(List<DomainLink> linkList){

        int i = 0;
        for (DomainLink domainLink : linkList){
            if (i <= (linkList.size()-1)){
                nextNodeConnectors.put(domainLink.getLink().getSource().getSourceNode().getValue() ,domainLink.getLink().getDestination().getDestTp().getValue());
            }
        }
    }

    private double monitorPacketLoss(){
        Integer currentIngressPackets = PacketProcessing.ingressUdpPackets - ingressPackets;
        Integer currentEgressPackets = PacketProcessing.egressUdpPackets - egressPackets;
        Integer lostUdpPackets = currentIngressPackets - currentEgressPackets;
        System.out.println("Packets " + currentIngressPackets + " " + currentEgressPackets);

        ingressPackets = PacketProcessing.ingressUdpPackets;
        egressPackets = PacketProcessing.egressUdpPackets;

        double packetLoss;
        if (lostUdpPackets > 0){
            packetLoss = (double)lostUdpPackets/currentIngressPackets;
        }
        else{
            packetLoss = 0;
        }
        return packetLoss;
    }

    private int findBits(){

        Integer currentIngressBits = PacketProcessing.ingressBits - ingressBits;
        Integer currentEgressBits = PacketProcessing.egressBits - egressBits;
        System.out.println("Bits " + currentIngressBits + " " + currentEgressBits);
        ingressBits = PacketProcessing.ingressBits;
        egressBits = PacketProcessing.egressBits;
        return currentEgressBits;
    }

    public float computeN(float frameRate){
        float N = -1;
        Long timeNow = System.currentTimeMillis();
        if (lastQoEEstimationTime == 0L){
            if (PacketProcessing.videoStartTime != 0L && PacketProcessing.videoHasStarted) {
                Long diff = timeNow - PacketProcessing.videoStartTime;
         //       System.out.println("Time now " + timeNow + " - video start time " + PacketProcessing.videoHasStarted + " = " + diff);
                float timeElapsed = (timeNow - PacketProcessing.videoStartTime)/(float)1000;
                N = frameRate*timeElapsed;

            }
        }
        else {
            Long diff = timeNow - lastQoEEstimationTime;
       //     System.out.println("Time now " + timeNow + " - last time " + lastQoEEstimationTime + " = " + diff);
            float timeElapsed = (timeNow - lastQoEEstimationTime)/(float)1000;
            N  = frameRate*timeElapsed;
        }
        lastQoEEstimationTime = timeNow;

        return N;
    }

    /**
     * The method which computes the total delay of a path.
     *
     * @param delays    A list containing the delay of each link in the path.
     * @return          It returns the path's total delay.
     */
    public Long computeTotalDelay(List<Long> delays){
        Long totalDelay = 0L;
        for (Long delay : delays){
            totalDelay += delay;
        }
        return totalDelay;
    }

    public float computeVideoFPS(String videoLocation){

        float frameRate;
        System.out.println("Computing fps");

        String command = "ffmpeg -i " + videoLocation + " -hide_banner";
  //      System.out.println(command);
        ExecuteShellCommand obj = new ExecuteShellCommand();
        String output = obj.executeCommand(command);
        if (output != null) {
     //       System.out.println(output);
            String[] outputParts = output.split(",");
            for (int i = 0; i < outputParts.length; i++){
                if (outputParts[i].contains("fps")){
                    String fps = outputParts[i];
                    String[] fpsParts = fps.split(" ");
                    if (fpsParts.length > 2){
                        frameRate = Float.parseFloat(fpsParts[1]);
                        System.out.println(frameRate);
                        return frameRate;
                    }
                    break;
                }
            }
        }
        return -1;
    }

}


