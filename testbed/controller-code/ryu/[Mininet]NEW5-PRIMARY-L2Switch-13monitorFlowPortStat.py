# encoding: utf-8

# Copyright (C) 2011 Nippon Telegraph and Telephone Corporation.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
# implied.
# See the License for the specific language governing permissions and
# limitations under the License.


"""
An OpenFlow 1.3 L2 learning switch implementation.
"""

from ryu.base import app_manager
from ryu.controller import ofp_event
from ryu.controller.handler import MAIN_DISPATCHER
from ryu.controller.handler import CONFIG_DISPATCHER,HANDSHAKE_DISPATCHER
from ryu.controller.handler import DEAD_DISPATCHER
from ryu.controller.handler import set_ev_cls
#from ryu.ofproto import ofproto_v1_0
from ryu.ofproto import ofproto_v1_3
from ryu.ofproto import ofproto_v1_3_parser
#from ryu.lib.mac import haddr_to_bin
from ryu.lib.packet import packet
from ryu.lib.packet import ethernet,tcp
from ryu.lib.packet import ether_types

from ryu.lib import hub
import datetime
import time
import logging
import os
import sys
from ryu import utils

class SimpleSwitch13(app_manager.RyuApp):
    OFP_VERSIONS = [ofproto_v1_3.OFP_VERSION]
	
    def __init__(self, *args, **kwargs):
        super(SimpleSwitch13, self).__init__(*args, **kwargs)
        self.mac_to_port = {}
        self.datapaths = {}
        self.timestamp = {} #used to do moniter time count
        self.averageDelay = {} #used to moniter average delay of monitor a flow data
        self.flowStatusQueryCount = {} #used to count how many times query the flow status
        self.totalDelay = {} #used to store total delay      
        self.flowStatCount = {} #used to count how many times flow stat reply get
        self.pktSplit = {} # used to test split stat reply packet, should first test how many pkt are there
        self.dealSplitPktTime = {} # used to measure every split stat reply pkt controller process time
        #first must check wireshark about how many split pkt are, then this value must be changed
        self.SPLIT_PKT_NUM = {} # used to define one flow stat request get how many pkt, for small reply default is 1, eg. 10 for 5014 flows with mss size = 65490 each jumbo pkt in mininet, in real network the size could be 1500, so 5014 flows means 430 pkt, horrible!!!
        self.MAX_FLOWS_PER_PACKET = {} # in OVS, one packet can contain 511 flows with 128 Byte Per Flow State, Max Packet is 65490 Bytes
        self.got_max_pkt = {} # indicates last flow stat reply contains a MAX FLOW STAT NUMS
        self.stored_flow_state_info = {} # for cache the last time flow stat pkt info
        self.averageControllerDelay = {} # used to monitor average process delay on controller, for flow stat
        self.totalControllerDelay = {} # used to monitor total controller delay, for flow stat

        self.flow_stat_request_count = {} #every time a request sent to collect flow state +1 for each switch
        self.flow_stat_reply_count = {} #passive increase by compare with flow_stat_request_count, if less, indicates a new stat round, and will +1        
        
        #define max table-id in use
        #if it is not 0, eg. it is 3, then table 0,1,2 will add a match-all entry to let every packet GOTO next table as table pipeline
        self.MAX_USE_TABLE_ID = 0 # default 0


        #test packet_in and flow mod
        self.firstFlowMod = 0 #used to judge if this is the first Flow Mod
        self.flow_mod_timestamp = 0
        self.flow_mod_old_timestamp = 0
        self.averageFlowModDelay = 0
        self.flow_mod_count = 0
        self.flow_mod_totalDelay = 0
        self.pkt_in_no_process_timestamp = 0
        self.pkt_in_count = 0

        #log file
        self.logfile = '/tmp/ryu-testFlowStatRead20220905.log'
        if os.path.exists(self.logfile):
            os.remove(self.logfile)
        self.fileHandler = logging.FileHandler('/tmp/ryu-testFlowStatRead20220905.log')
        self.logger.addHandler(self.fileHandler)

        #used to judge the first flow add event start time
        self.flowAddStart = 0
        self.flowAddCount = 0
        self.flowAddStartTime = 0
        self.flowAddEndTime = 0
        self.flowAddAverageTime = 0
        #first flow error flag
        self.flowError = 0

        #used to collect info from port
        self.portStats={} # save infomation of every port current stats for each switch(dpid)
        self.portRequestTime={} # PortStatsRequest send timestamp for each dp
        self.portStatCount = {} # Stats Round Count for each dp
        self.portStatTotalTime = {} # Stats total cost time for n rounds
        self.portStatAverageTime = {} # Stats average time
        self.portStatTotalControllerCost = {} # PortStats total controller cost time 
        self.portStatAverageControllerCost = {} # PortStats average controller cost time 
        
        #use hub to start a thread to monitor states of some flows
        self.monitor_thread1 = hub.spawn(self._monitor)
        #self.monitor_thread2 = hub.spawn(self._monitor_aggr)
        #self.monitor_thread3 = hub.spawn(self._monitor_port)
        #self.flowAdd_thread =hub.spawn(self._flow_add_to_full)
        #self.flowAdd_thread = hub.spawn(self._add_101_flows)

        #trigger role change from slave to master to test master display changes
        #self.rolechange_thread = hub.spawn(self._role_change, 1, 2)

    def _role_change(self, id, role):
        hub.sleep(30)
        datapath = None
        if id not in self.datapaths:
            return
        else:
            datapath = self.datapaths[id]
        if role < 0 or role > 4:
            self.logger.info("Error in role number! below 0 or above 4!")
        self.logger.info("Try change Role into : %s !!(0-nochange,1-equal,2-master,3-slave)", role)
        if datapath!=None:
            self.send_role_request(datapath, role)

    def _monitor(self):
        hub.sleep(5)
        while True:
            for id in self.datapaths:
                self.send_flow_stats_request(self.datapaths[id])
            #self.send_flow_stats_request(self.datapaths[1])
            #self.send_flow_stats_request(self.datapaths[2])
            hub.sleep(10) #2 seconds is too short for s1 4500 flows and s2 4500 flows to simultaneously add flow

    def _monitor_aggr(self):
        hub.sleep(5)
        while True:
            for id in self.datapaths:
                self.send_aggregate_stats_request(self.datapaths[id])
            hub.sleep(2)

    def _monitor_port(self):
        hub.sleep(5)
        while True:
            for id in self.datapaths:
                self.send_port_stats_request(self.datapaths[id])
            #self.send_flow_stats_request(self.datapaths[1])
            #self.send_flow_stats_request(self.datapaths[2])
            hub.sleep(5) #2 seconds is too short for s1 4500 flows and s2 4500 flows to simultaneously add flow
    
    def _add_101_flows(self):
        hub.sleep(15)
        dpid=1
        datapath = self.datapaths[dpid]
        parser= datapath.ofproto_parser
        self.logger.info("\n======= START add flow into datapath: %s =======\n",dpid)
        for i in range(2048,5149):
            match = parser.OFPMatch(eth_type=0x800,eth_src='00:00:00:00:00:01',eth_dst='00:00:00:00:00:02',ip_proto=6,tcp_dst=i)
            actions = [parser.OFPActionOutput(2)]
            self.add_flow(datapath,1,'00:00:00:00:00:02',actions,match)
            hub.sleep(0.01)
        self.send_aggregate_stats_request(datapath)

    def _flow_add_to_full(self):
        hub.sleep(30)
        datapath = None
        if len(self.datapaths) > 0:
            for id in self.datapaths:
                datapath = self.datapaths[id]
                break
            self.logger.info("\n======= START add flow into datapath: %s =======\n",datapath.id)
            parser = datapath.ofproto_parser
            for i in range(1,2):
                for j in range(1,65535):
                    match = parser.OFPMatch(in_port=2,eth_src='00:00:00:00:00:02',eth_dst='00:00:00:00:00:01',eth_type=0x800,ip_proto=6,tcp_src=i,tcp_dst=j)
                    actions = [parser.OFPActionOutput(1)]
                    self.add_flow(datapath,2,'00:00:00:00:00:01',actions,match)
                    hub.sleep(0.01)
        self.send_aggregate_stats_request(datapath)

    @set_ev_cls(ofp_event.EventOFPStateChange,[MAIN_DISPATCHER, DEAD_DISPATCHER])
    def _state_change_handler(self, ev):
        datapath = ev.datapath
        if ev.state == MAIN_DISPATCHER:
            if datapath.id not in self.datapaths:
                #self.logger.info('register switch by datapath ID: %016x', datapath.id)
                self.logger.info('register switch by datapath ID: %s', datapath.id)
                self.datapaths[datapath.id] = datapath
                #set this controller to be MASTER of the switch, MASTER = 2 SLAVE = 3
                self.send_role_request(datapath,2)
                #add table miss entry
                self.add_table_miss(datapath,self.MAX_USE_TABLE_ID)
                #self.add_table_miss(datapath,1)

                self.flow_stat_request_count[datapath.id] = 0
                self.flow_stat_reply_count[datapath.id] = 0
                self.stored_flow_state_info[datapath.id] = {}
                self.averageDelay[datapath.id] = 0 #used to moniter average delay of monitor a flow data
                self.flowStatusQueryCount[datapath.id] = 0 #used to count how many times query the flow status
                self.totalDelay[datapath.id] = 0 #used to store total delay      
                self.flowStatCount[datapath.id] = 0 #used to count how many times flow stat reply get
                self.pktSplit[datapath.id] = 0 # used to test split stat reply packet, should first test how many pkt are there
                self.dealSplitPktTime[datapath.id] = 0 # used to measure every split stat reply pkt controller process time
                #first must check wireshark about how many split pkt are, then this value must be changed
                self.SPLIT_PKT_NUM[datapath.id] = 1 # used to define one flow stat request get how many pkt, for small reply default is 1, eg. 10 for 5014 flows with mss size = 65490 each jumbo pkt in mininet, in real network the size could be 1500, so 5014 flows means 430 pkt, horrible!!!
                self.MAX_FLOWS_PER_PACKET[datapath.id] = 511 # in OVS, one packet can contain 511 flows with 128 Byte Per Flow State, Max Packet is 65490 Bytes
                self.got_max_pkt[datapath.id] = 0 # indicates last flow stat reply contains a MAX FLOW STAT NUMS
                #self.logger.info("%d,%d",datapath.id,self.pktSplit[datapath.id])
                self.averageControllerDelay[datapath.id] = 0 # used to monitor average process delay on controller, for flow stat
                self.totalControllerDelay[datapath.id] = 0 # used to monitor total controller delay, for flow stat
                
                self.portStats[datapath.id]={} #init port stats for every dpid
                self.portRequestTime[datapath.id]= 0 # when this round request start for each dp
                self.portStatTotalTime[datapath.id] = 0 # total delay of n rounds time for each dp
                self.portStatCount[datapath.id] = 0 # how many rounds for each dp
                self.portStatAverageTime[datapath.id] = 0 # PortStats average time
                self.portStatTotalControllerCost[datapath.id] = 0 # PortStats average controller cost time 
                self.portStatAverageControllerCost[datapath.id] = 0 # PortStats total controller cost time 
        elif ev.state == DEAD_DISPATCHER:
            if datapath.id in self.datapaths:
                self.logger.info('unregister switch by datapath ID: %s', datapath.id)
                del self.datapaths[datapath.id]

    def add_flow(self, datapath, in_port, dst, actions,m,table_id = 0):
        #self.logger.info("FLOW-ADD:datapath:%s,in_port:%s,dst:%s,actions:%s",datapath.id,in_port,dst,actions)
        ofproto = datapath.ofproto
        ofp_parser = datapath.ofproto_parser
        priority=ofproto.OFP_DEFAULT_PRIORITY
        dpid = datapath.id

        match = datapath.ofproto_parser.OFPMatch(in_port=in_port, eth_dst=dst)
        if m != None:
            match = m
            priority = ofproto.OFP_DEFAULT_PRIORITY + 1
            

        instructions = [ofp_parser.OFPInstructionActions(ofproto.OFPIT_APPLY_ACTIONS,actions)]
        #use idle_timeout=15 hard_timeout=30 to limit flow rule exist time
        #set to both 0 to be effect forever
        mod = datapath.ofproto_parser.OFPFlowMod(
            datapath=datapath, match=match, cookie=0,cookie_mask=0,table_id=table_id,
            command=ofproto.OFPFC_ADD, idle_timeout=0, hard_timeout=0,
            priority=priority,buffer_id=ofproto.OFP_NO_BUFFER,
            flags=ofproto.OFPFF_SEND_FLOW_REM, instructions=instructions)
        datapath.send_msg(mod)
        #use to judge add flow start
        if self.flowAddStart == 0:
            self.flowAddStart = 1
            self.flowAddStartTime = time.time()
            self.logger.info('\n==== Flow Add Start ====\n')
        self.flowAddCount += 1;
        self.logger.info("====== Flow Add Count : %s ======",self.flowAddCount)
        #self.logger.info("FLOW-ADD:datapath:%s,in_port:%s,dst:%s,match:%s,actions:%s\n",datapath.id,in_port,dst,match,actions)
        #self.logger.info("============================================\n")

        #test flow mod duration
        if self.firstFlowMod > 0:
            self.flow_mod_timestamp = time.time()
            #self.logger.info("New Time : %f",self.flow_mod_timestamp)
            #self.logger.info("Old Time : %f",self.flow_mod_old_timestamp)
            pktInRoundTime = (self.flow_mod_timestamp - self.flow_mod_old_timestamp) * 1000 * 1000 # in micro second
            self.flow_mod_count += 1
            self.flow_mod_totalDelay += pktInRoundTime
            self.averageFlowModDelay = self.flow_mod_totalDelay / self.flow_mod_count
            self.logger.info("@Datapath ID: %d - Flow Mod delay for this packet is :%f us", dpid, pktInRoundTime)
            self.logger.info("@@Average Time for Flow Mod delay is :%f us", self.averageFlowModDelay)
            self.flow_mod_old_timestamp = self.flow_mod_timestamp
        else:
            self.firstFlowMod = 1
            self.flow_mod_old_timestamp = time.time()

    def del_flow(self, datapath,table_id=0):
        #delete all flows in datapath
        self.logger.info("FLOW-DEL:datapath:%s",datapath.id)
        ofproto = datapath.ofproto
        ofp_parser = datapath.ofproto_parser

        match = datapath.ofproto_parser.OFPMatch()

        #instructions = [ofp_parser.OFPInstructionActions(ofproto.OFPIT_CLEAR_ACTIONS, [])]
        mod = datapath.ofproto_parser.OFPFlowMod(
            datapath=datapath, match=match, cookie=0,cookie_mask=0,table_id=table_id,
            command=ofproto.OFPFC_DELETE, idle_timeout=0, hard_timeout=0,
            priority=ofproto.OFP_DEFAULT_PRIORITY,out_port=ofproto.OFPP_ANY,out_group=ofproto.OFPG_ANY)
        #self.logger.info("DELETE INFO:%s",mod)
        datapath.send_msg(mod)
    
    #def send_set_config(self, datapath):
        #ofp = datapath.ofproto
        #ofp_parser = datapath.ofproto_parser
        ## last parameter is miss_send_len
        ## which indicates how many bytes will send to controller when table miss occured
        #req = ofp_parser.OFPSetConfig(datapath, ofp.OFPC_FRAG_NORMAL, 182)
        #datapath.send_msg(req)

    #@set_ev_cls(ofp_event.EventOFPGetConfigReply, MAIN_DISPATCHER)
    #def get_config_reply_handler(self, ev):
        #msg = ev.msg
        #dp = msg.datapath
        #ofp = dp.ofproto
        #flags = []

        #if msg.flags & ofp.OFPC_FRAG_NORMAL:
            #flags.append('NORMAL')
        #if msg.flags & ofp.OFPC_FRAG_DROP:
            #flags.append('DROP')
        #if msg.flags & ofp.OFPC_FRAG_REASM:
            #flags.append('REASM')
        #self.logger.debug('OFPGetConfigReply received: '
                          #'flags=%s miss_send_len=%d',
                          #','.join(flags), msg.miss_send_len)

    '''
    def add_table_miss(self,datapath,table_id):
        ofproto = datapath.ofproto
        match = datapath.ofproto_parser.OFPMatch()
        #actions = [datapath.ofproto_parser.OFPActionOutput(ofproto.OFPP_CONTROLLER,ofproto.OFPCML_NO_BUFFER)]
        #OFPActionOutPut with 2 parameters, first in to controller preserved port, last is how many bytes send to controller, 65535 = OFPCML_NO_BUFFER
        #In Fact, 66 bytes cause an 182 bytes Packet-In Package
        actions = []
        priority = 0
        #if table_id == 1:
        actions = [datapath.ofproto_parser.OFPActionOutput(ofproto.OFPP_CONTROLLER,80)]
        #else:
            #actions = [datapath.ofproto_parser.OFPActionOutput(ofproto.OFPP_TABLE)]
            #priority = 1
        #instructions = [datapath.ofproto_parser.OFPInstructionActions(ofproto.OFPIT_WRITE_ACTIONS,actions)]
        instructions = [datapath.ofproto_parser.OFPInstructionActions(ofproto.OFPIT_APPLY_ACTIONS,actions)]
        mod = datapath.ofproto_parser.OFPFlowMod(
              datapath=datapath,match=match,cookie=0,cookie_mask=0,table_id=table_id,
              command=ofproto.OFPFC_ADD,idle_timeout=0,hard_timeout=0,
              priority=priority,buffer_id=ofproto.OFP_NO_BUFFER,flags=ofproto.OFPFF_SEND_FLOW_REM,
              instructions=instructions)
        datapath.send_msg(mod)
    '''

    def add_table_miss(self,datapath,table_id):
        ofproto = datapath.ofproto
        match = datapath.ofproto_parser.OFPMatch()
        if table_id < 0:
            self.logger.info("Error: Table miss insert error, Table-id < 0!")
            sys.exit(0)
        # before last table all goto next-table when table-miss occured
        for tid in range(0,table_id):
            instructions = [datapath.ofproto_parser.OFPInstructionGotoTable(tid+1)]
            mod = datapath.ofproto_parser.OFPFlowMod(
                  datapath=datapath,match=match,cookie=0,cookie_mask=0,table_id=tid,
                  command=ofproto.OFPFC_ADD,idle_timeout=0,hard_timeout=0,
                  priority=0,buffer_id=ofproto.OFP_NO_BUFFER,flags=ofproto.OFPFF_SEND_FLOW_REM,
                  instructions=instructions)
            datapath.send_msg(mod)

        # do last table table-miss assign
        actions = [datapath.ofproto_parser.OFPActionOutput(ofproto.OFPP_CONTROLLER,ofproto.OFPCML_NO_BUFFER)]
        #OFPActionOutPut with 2 parameters, first in to controller preserved port, last is how many bytes send to controller, 65535 = OFPCML_NO_BUFFER
        #In Fact, 66 bytes cause an 182 bytes Packet-In Package
        #actions = [datapath.ofproto_parser.OFPActionOutput(ofproto.OFPP_CONTROLLER,80)]
        #instructions = [datapath.ofproto_parser.OFPInstructionActions(ofproto.OFPIT_WRITE_ACTIONS,actions)]
        instructions = [datapath.ofproto_parser.OFPInstructionActions(ofproto.OFPIT_APPLY_ACTIONS,actions)]
        mod = datapath.ofproto_parser.OFPFlowMod(
              datapath=datapath,match=match,cookie=0,cookie_mask=0,table_id=table_id,
              command=ofproto.OFPFC_ADD,idle_timeout=0,hard_timeout=0,
              priority=0,buffer_id=ofproto.OFP_NO_BUFFER,flags=ofproto.OFPFF_SEND_FLOW_REM,
              instructions=instructions)
        datapath.send_msg(mod)


    @set_ev_cls(ofp_event.EventOFPSwitchFeatures, CONFIG_DISPATCHER)
    def _pre_add_table_miss(self,ev):
        msg = ev.msg
        datapath = msg.datapath
        self.logger.info('switch features ev %s', msg)

        datapath.id = msg.datapath_id

        # hacky workaround, will be removed. OF1.3 doesn't have
        # ports. An application should not depend on them. But there
        # might be such bad applications so keep this workaround for
        # while.
        if datapath.ofproto.OFP_VERSION < 0x04:
            datapath.ports = msg.ports
        else:
            datapath.ports = {}

        if datapath.ofproto.OFP_VERSION < 0x04:
            self.logger.debug('move onto main mode')
            ev.msg.datapath.set_state(MAIN_DISPATCHER)
        else:
            port_desc = datapath.ofproto_parser.OFPPortDescStatsRequest(
                datapath, 0)
            datapath.send_msg(port_desc)
        #xp edit
        #no table miss, so there is no packet in			
        #self.add_table_miss(datapath,0)
		

    @set_ev_cls(ofp_event.EventOFPPacketIn, MAIN_DISPATCHER)
    def _packet_in_handler(self, ev):
        #if self.flowError >= 1:
            #return
        self.pkt_in_no_process_timestamp = time.time()
        self.pkt_in_count += 1
        msg = ev.msg
        datapath = msg.datapath
        ofproto = datapath.ofproto
        parser = datapath.ofproto_parser

        #self.logger.info("============================================")
        #self.logger.info("a packet has reach!")
        pkt = packet.Packet(msg.data)
        eth = pkt.get_protocol(ethernet.ethernet)

        if eth.ethertype == ether_types.ETH_TYPE_LLDP:
            # ignore lldp packet
            return
        dst = eth.dst
        #self.logger.info("eth_dst: %s", dst)
        src = eth.src
        type_mac = eth.ethertype

        dpid = datapath.id
        #table_id = 0 # default use table 0
        table_id = self.MAX_USE_TABLE_ID # use the last flow table to insert flow entry
        self.mac_to_port.setdefault(dpid, {})

        pkgmatch = msg.match
        in_port = None
        if 'in_port' in pkgmatch:
            in_port = pkgmatch['in_port']
		
        #self.logger.info("packet in %s %s %s %s", dpid, src, dst, in_port)

        # learn a mac address to avoid FLOOD next time.
        if in_port != None:
            self.mac_to_port[dpid][src] = in_port

        #self.logger.info("Current mac_to_port = %s",self.mac_to_port)

        if dst in self.mac_to_port[dpid]:
            out_port = self.mac_to_port[dpid][dst]
        else:
            out_port = ofproto.OFPP_FLOOD

        actions = [datapath.ofproto_parser.OFPActionOutput(in_port)]
        actionsOut = [datapath.ofproto_parser.OFPActionOutput(out_port)]

        # install a flow to avoid packet_in next time
        
        if in_port != None and out_port != ofproto.OFPP_FLOOD:
            #test for TCP
            tcp_pkt = pkt.get_protocols(tcp.tcp)
            tcp_dst = 0
            tcp_src = 0


            if len(tcp_pkt)!=0:
                tcp_pkt = tcp_pkt[0]
                #self.logger.info(tcp_pkt)
                tcp_dst = tcp_pkt.dst_port
                tcp_src = tcp_pkt.src_port
                target_match1 = parser.OFPMatch(in_port=in_port,eth_src=src,eth_dst=dst,eth_type=type_mac,ip_proto=6,tcp_dst=tcp_dst)
                target_match2 = parser.OFPMatch(in_port=out_port,eth_src=dst,eth_dst=src,eth_type=type_mac,ip_proto=6,tcp_dst=tcp_src)
                self.add_flow(datapath, out_port, src, actions,target_match2,table_id)
                self.add_flow(datapath, in_port, dst, actionsOut,target_match1,table_id)

            #self.add_flow(datapath, out_port, src, actions,None)
            #self.add_flow(datapath, in_port, dst, actionsOut,None)
        
        data = None
        if msg.buffer_id == ofproto.OFP_NO_BUFFER:
            data = msg.data

        out = datapath.ofproto_parser.OFPPacketOut(
            datapath=datapath, buffer_id=msg.buffer_id, in_port=in_port,
            actions=actionsOut, data=data)
        datapath.send_msg(out)

        # calc process pkt_in controller process time
        #self.logger.info("@@@@@@Packet In No %d, Datapath ID: %d, Controller Delay is %d us@@@@@@\n",self.pkt_in_count, dpid, (time.time() - self.pkt_in_no_process_timestamp) * 1000 * 1000)
        

    @set_ev_cls(ofp_event.EventOFPPortStatus, MAIN_DISPATCHER)
    def _port_status_handler(self, ev):
        msg = ev.msg
        reason = msg.reason
        port_no = msg.desc.port_no

        ofproto = msg.datapath.ofproto
        if reason == ofproto.OFPPR_ADD:
            self.logger.info("port added %s", port_no)
        elif reason == ofproto.OFPPR_DELETE:
            self.logger.info("port deleted %s", port_no)
        elif reason == ofproto.OFPPR_MODIFY:
            self.logger.info("port modified %s", port_no)
        else:
            self.logger.info("Illeagal port state %s %s", port_no, reason)

        self.logger.info("port msg: %s", msg.desc)

    @set_ev_cls(ofp_event.EventOFPFlowStatsReply, MAIN_DISPATCHER)
    def _flow_stats_reply_handler(self, ev):
        dpid = ev.msg.datapath.id
        #pktFlowNum = len(ev.msg.body) # in ovs, max packet is 511 flow
        #if self.pktSplit[dpid] > self.SPLIT_PKT_NUM[dpid] and pktFlowNum == self.MAX_FLOWS_PER_PACKET[dpid]:
        if self.flow_stat_reply_count[dpid] < self.flow_stat_request_count[dpid]:
            self.dealSplitPktTime[dpid] = 0
            self.pktSplit[dpid] = 0
        roundTimeNoDisp = (time.time() - self.timestamp[dpid] - self.dealSplitPktTime[dpid] / 1000 / 1000) * 1000 * 1000 # by micro second
        flows = []
        flowNum = 0
               
        self.pktSplit[dpid] += 1

        if self.flow_stat_reply_count[dpid] < self.flow_stat_request_count[dpid]:
            if self.flowStatCount[dpid] > 0:
                self.logger.info("DataPath No.%d - Flow status request-reply round time:%d us",dpid, self.stored_flow_state_info[dpid]["roundTimeNoDisp"])
                self.logger.info("DataPath No.%d - Flow status request-reply round time With Display:%d us, Controller Delay: %d us",dpid, self.stored_flow_state_info[dpid]["roundTimeNoDisp"] + self.stored_flow_state_info[dpid]["dealSplitPktTime"], self.stored_flow_state_info[dpid]["dealSplitPktTime"])
                self.logger.info("Average Time of delay is :%d us", self.stored_flow_state_info[dpid]["averageDelay"])
                self.logger.info("Average Process delay of Controller is :%d us",self.stored_flow_state_info[dpid]["averageControllerDelay"])
                self.flowStatusQueryCount[dpid] = self.stored_flow_state_info[dpid]["flowStatusQueryCount"]
                self.totalDelay[dpid] = self.stored_flow_state_info[dpid]["totalDelay"]
                self.averageDelay[dpid] = self.stored_flow_state_info[dpid]["averageDelay"]
                self.totalControllerDelay[dpid] = self.stored_flow_state_info[dpid]["totalControllerDelay"]
                self.averageControllerDelay[dpid] = self.stored_flow_state_info[dpid]["averageControllerDelay"]
            self.flow_stat_reply_count[dpid] += 1


        if self.pktSplit[dpid] == 1:
            self.flowStatCount[dpid] += 1
        
        
        #self.logger.info("Split Pkt No %d received, %d msgs inside!",self.pktSplit[dpid],len(ev.msg.body))
        for stat in ev.msg.body:
            flowNum += 1
            
            flows.append('datapath_id=%s '
                         'table_id=%s '
                         'duration_sec=%d duration_nsec=%d '
                         'priority=%d '
                         'idle_timeout=%d hard_timeout=%d flags=0x%04x '
                         'cookie=%d packet_count=%d byte_count=%d '
                         'match=%s instructions=%s' %
                         (dpid,
			  stat.table_id,
                          stat.duration_sec, stat.duration_nsec,
                          stat.priority,
                          stat.idle_timeout, stat.hard_timeout, stat.flags,
                          stat.cookie, stat.packet_count, stat.byte_count,
                          stat.match, stat.instructions))
        #self.logger.info('\n====================================================\n\
#FlowStats: %s\n====================================================\n', flows)
        
        roundTime = (time.time() - self.timestamp[dpid] - self.dealSplitPktTime[dpid] / 1000 / 1000) * 1000 * 1000 # by micro second
        self.logger.info("DataPath ID:%d, Round: %d - Stat Reply Packet No. %d - Get %d flows status - This Packet Accumulate Delay is %d us",dpid, self.flowStatCount[dpid],self.pktSplit[dpid],flowNum,roundTimeNoDisp)
        self.dealSplitPktTime[dpid] += roundTime - roundTimeNoDisp
        self.logger.info("Controller Process Time for this round: %d us",self.dealSplitPktTime[dpid])
        #self.logger.info("RT-nodisp:%d us, RT :%d us, DT %d us, OT %d us\n********\n",roundTimeNoDisp,roundTime,self.dealSplitPktTime[dpid],self.timestamp[dpid])
        
        self.stored_flow_state_info[dpid]["dpid"] = dpid
        self.stored_flow_state_info[dpid]["roundTimeNoDisp"] = roundTimeNoDisp
        self.stored_flow_state_info[dpid]["roundTime"] = roundTime
        self.stored_flow_state_info[dpid]["dealSplitPktTime"] = self.dealSplitPktTime[dpid]
        self.stored_flow_state_info[dpid]["flowStatusQueryCount"] = self.flowStatusQueryCount[dpid] + 1
        self.stored_flow_state_info[dpid]["totalDelay"] = self.totalDelay[dpid] + roundTimeNoDisp
        self.stored_flow_state_info[dpid]["averageDelay"] = self.stored_flow_state_info[dpid]["totalDelay"] / self.stored_flow_state_info[dpid]["flowStatusQueryCount"]
        self.stored_flow_state_info[dpid]["totalControllerDelay"] = self.totalControllerDelay[dpid] + self.dealSplitPktTime[dpid]
        self.stored_flow_state_info[dpid]["averageControllerDelay"] = self.stored_flow_state_info[dpid]["totalControllerDelay"] / self.stored_flow_state_info[dpid]["flowStatusQueryCount"]


    def send_flow_stats_request(self, datapath):
        """
        match field has following arguments:
        ================ =============== ==================================
        Argument         Value           Description
        ================ =============== ==================================
        in_port          Integer 32bit   Switch input port
        in_phy_port      Integer 32bit   Switch physical input port
        metadata         Integer 64bit   Metadata passed between tables
        eth_dst          MAC address     Ethernet destination address
        eth_src          MAC address     Ethernet source address
        eth_type         Integer 16bit   Ethernet frame type
        vlan_vid         Integer 16bit   VLAN id
        vlan_pcp         Integer 8bit    VLAN priority
        ip_dscp          Integer 8bit    IP DSCP (6 bits in ToS field)
        ip_ecn           Integer 8bit    IP ECN (2 bits in ToS field)
        ip_proto         Integer 8bit    IP protocol
        ipv4_src         IPv4 address    IPv4 source address
        ipv4_dst         IPv4 address    IPv4 destination address
        tcp_src          Integer 16bit   TCP source port
        tcp_dst          Integer 16bit   TCP destination port
        udp_src          Integer 16bit   UDP source port
        udp_dst          Integer 16bit   UDP destination port
        sctp_src         Integer 16bit   SCTP source port
        sctp_dst         Integer 16bit   SCTP destination port
        icmpv4_type      Integer 8bit    ICMP type
        icmpv4_code      Integer 8bit    ICMP code
        arp_op           Integer 16bit   ARP opcode
        arp_spa          IPv4 address    ARP source IPv4 address
        arp_tpa          IPv4 address    ARP target IPv4 address
        arp_sha          MAC address     ARP source hardware address
        arp_tha          MAC address     ARP target hardware address
        ipv6_src         IPv6 address    IPv6 source address
        ipv6_dst         IPv6 address    IPv6 destination address
        ipv6_flabel      Integer 32bit   IPv6 Flow Label
        icmpv6_type      Integer 8bit    ICMPv6 type
        icmpv6_code      Integer 8bit    ICMPv6 code
        ipv6_nd_target   IPv6 address    Target address for ND
        ipv6_nd_sll      MAC address     Source link-layer for ND
        ipv6_nd_tll      MAC address     Target link-layer for ND
        mpls_label       Integer 32bit   MPLS label
        mpls_tc          Integer 8bit    MPLS TC
        mpls_bos         Integer 8bit    MPLS BoS bit
        pbb_isid         Integer 24bit   PBB I-SID
        tunnel_id        Integer 64bit   Logical Port Metadata
        ipv6_exthdr      Integer 16bit   IPv6 Extension Header pseudo-field
        pbb_uca          Integer 8bit    PBB UCA header field
                                        (EXT-256 Old version of ONF Extension)
        tcp_flags        Integer 16bit   TCP flags
                                        (EXT-109 ONF Extension)
        actset_output    Integer 32bit   Output port from action set metadata
                                        (EXT-233 ONF Extension)
        ================ =============== ==================================

        if you want to use wildcards, follow this:
            match_field = dict(eth_src = ('00:00:00:00:00:01','ff:ff:ff:ff:ff:f0'),
                               eth_dst = ('00:00:00:00:00:04','ff:ff:ff:ff:ff:f4'),
                               ipv4_src = ('10.0.0.1','255.255.255.0'),
                               ipv4_dst = ('10.0.0.4','255.255.255.0'),
                               tcp_src = (80,2),
                               tcp_dst = (27,3)
            )
        if you want to match mac address:
            match_field = dict(eth_src = ('00:00:00:00:00:01','ff:ff:ff:ff:ff:f0'),
                               eth_dst = ('00:00:00:00:00:04','ff:ff:ff:ff:ff:f4'))
            match = ofp_parser.OFPMatch(**match_field)
            self._send_flow_stats_request(_datapath,match)

        if you want to match ip address :
            match_field = dict(eth_type=0x800,
                               ipv4_src = ('10.0.0.1','255.255.255.0'),
                               ipv4_dst = ('10.0.0.4','255.255.255.0'))
            match = ofp_parser.OFPMatch(**match_field)
            self._send_flow_stats_request(_datapath,match)

        if you want to match tcp port :
            match_field = dict(eth_type=0x800,
                               ip_proto=6,
                               tcp_src=80,
                               tcp_dst = 27)
            match = ofp_parser.OFPMatch(**match_field)
            self._send_flow_stats_request(_datapath,match)
        """
        ofp = datapath.ofproto
        ofp_parser = datapath.ofproto_parser

        cookie = cookie_mask = 0
        #match = ofp_parser.OFPMatch(in_port=1)
        match = ofp_parser.OFPMatch() #this matches all package, for H3C S5130 it will crack down,S5120 no problem!

        #match first 15 dst from 00:00:00:00:00:00:01 to 00:00:00:00:00:0f
        #match_field = dict(eth_dst = ('00:00:00:00:00:00','ff:ff:ff:ff:ff:f0'))
        #match = ofp_parser.OFPMatch(**match_field)
        #match = ofp_parser.OFPMatch(eth_type=0x800,eth_src='00:00:00:00:00:01',eth_dst='00:00:00:00:00:02',ip_proto=6,tcp_dst=(0,0)) # match wildcard flows, n flows with mask = 65536 - n, n must = 2 exp k 
        #match = ofp_parser.OFPMatch(eth_type=0x800,eth_src='00:00:00:00:00:01',eth_dst='00:00:00:00:00:02',ip_proto=6,tcp_dst=2048) #match only 1 flow

        table_id = ofp.OFPTT_ALL
        #table_id = 1
        #table_id = self.MAX_USE_TABLE_ID
        req = ofp_parser.OFPFlowStatsRequest(datapath, 0,
                                             table_id,
                                             ofp.OFPP_ANY, ofp.OFPG_ANY,
                                             cookie, cookie_mask,
                                             match)
        self.timestamp[datapath.id] = time.time()
        datapath.send_msg(req)
        self.flow_stat_request_count[datapath.id] += 1

    def send_role_request(self, datapath, controllerRole):
        ofp = datapath.ofproto
        ofp_parser = datapath.ofproto_parser

        req = ofp_parser.OFPRoleRequest(datapath, controllerRole, 0)
        datapath.send_msg(req)
        role = "NOCHANGE"
        if controllerRole == ofp.OFPCR_ROLE_EQUAL:
            role = "EQUAL"
        elif controllerRole == ofp.OFPCR_ROLE_MASTER:
            role = "MASTER"
        elif controllerRole == ofp.OFPCR_ROLE_SLAVE:
            role = "SLAVE"
        self.logger.info("====SET controller to be: %s to datapath: %s !! =====\n", role,datapath.id)

    def send_role_query(self, datapath):
        ofp = datapath.ofproto
        ofp_parser = datapath.ofproto_parser

        req = ofp_parser.OFPRoleRequest(datapath, None, 0)
        datapath.send_msg(req)
        self.logger.info("Send controller role query msg to datapath：%s", datapath.id)


    @set_ev_cls(ofp_event.EventOFPRoleReply, MAIN_DISPATCHER)
    def role_reply_handler(self, ev):
        msg = ev.msg
        dp = msg.datapath
        ofp = dp.ofproto

        if msg.role == ofp.OFPCR_ROLE_NOCHANGE:
            role = 'NOCHANGE'
        elif msg.role == ofp.OFPCR_ROLE_EQUAL:
            role = 'EQUAL'
        elif msg.role == ofp.OFPCR_ROLE_MASTER:
            role = 'MASTER'
        elif msg.role == ofp.OFPCR_ROLE_SLAVE:
            role = 'SLAVE'
        else:
            role = 'unknown'

        self.logger.info('OFPRoleReply received: '
                          'role=%s generation_id=%d',
                          role, msg.generation_id)

	#send a request to get aggregated status for a datapath
    def send_aggregate_stats_request(self, datapath):
        ofp = datapath.ofproto
        ofp_parser = datapath.ofproto_parser

        cookie = cookie_mask = 0
        #match = ofp_parser.OFPMatch(in_port=1)
        match = ofp_parser.OFPMatch() # match all flows
        #match = ofp_parser.OFPMatch(eth_type=0x800,eth_src='00:00:00:00:00:01',eth_dst='00:00:00:00:00:02',ip_proto=6,tcp_dst=(2048,65408))
        #match = ofp_parser.OFPMatch(eth_type=0x800,eth_src='00:00:00:00:00:01',eth_dst='00:00:00:00:00:02',ip_proto=6,tcp_dst=2048)
        table_id = ofp.OFPTT_ALL
        #table_id = 0
        #table_id = self.MAX_USE_TABLE_ID
        req = ofp_parser.OFPAggregateStatsRequest(datapath, 0,
                                                  table_id,
                                                  ofp.OFPP_ANY,
                                                  ofp.OFPG_ANY,
                                                  cookie, cookie_mask,
                                                  match)
        self.timestamp[datapath.id] = time.time()
        datapath.send_msg(req)

    @set_ev_cls(ofp_event.EventOFPAggregateStatsReply, MAIN_DISPATCHER)
    def aggregate_stats_reply_handler(self, ev):
        dpid = ev.msg.datapath.id
        roundTimeNoDisp = (time.time() - self.timestamp[dpid]) * 1000 * 1000 # by micro seconds
        #below is for test Flow Table Overflow by flow installation
        self.flowAddEndTime = time.time()
        self.flowStatCount[dpid] += 1
        body = ev.msg.body
        self.logger.info('datapath_id: %s - AggregateStats: packet_count=%d byte_count=%d '
                          'flow_count=%d',
                          dpid,body.packet_count, body.byte_count,
                          body.flow_count)
        roundTime = (time.time() - self.timestamp[dpid]) * 1000 * 1000 # by micro seconds
        self.logger.info("\nDataPath ID: %d - Aggre Stat Reply Round %d ",dpid,self.flowStatCount[dpid])
        self.logger.info("Flow status aggregate request-reply round time: %d us", roundTimeNoDisp)
        self.logger.info("Flow status aggregate request-reply round time With Display: %d us, Controller Process Time: %d us", roundTime, roundTime - roundTimeNoDisp)
        #in per flow measure use to collect info in first error, comment the 4 sentense below
        self.flowStatusQueryCount[dpid] += 1
        self.totalDelay[dpid] += roundTimeNoDisp
        self.averageDelay[dpid] = self.totalDelay[dpid] / self.flowStatusQueryCount[dpid]
        self.logger.info("Average Time of delay is :%d us\n====================\n", self.averageDelay[dpid])
        # comment to end here

        #calculate flow mod average Time
        if self.flowError == 2:
            self.logger.info("\n==============================\nFlow Add Start at: %s",self.flowAddStartTime)
            self.logger.info("Flow Add  End  at: %s",self.flowAddEndTime)
            self.logger.info("Total flows are: %s",body.flow_count)
            self.flowAddAverageTime = (self.flowAddEndTime - self.flowAddStartTime) * 1000 * 1000 / int(body.flow_count)
            self.logger.info("AVERAGE FLOW ADD TIME is: %s\n==============================",self.flowAddAverageTime)
            self.flowError = 3

        #print last round flow stat average
        if self.flowError == 1:
            self.logger.info("\n===============================\nLast Round Flow Stat Before Error is Round: %d, DataPath No.%d - Flow status request-reply round time:%d us",self.stored_flow_state_info[dpid]["flowStatusQueryCount"], dpid, self.stored_flow_state_info[dpid]["roundTimeNoDisp"])
            self.logger.info("DataPath No.%d - Flow status request-reply round time With Display:%d us, Controller Delay: %d us",dpid, self.stored_flow_state_info[dpid]["roundTimeNoDisp"] + self.stored_flow_state_info[dpid]["dealSplitPktTime"], self.stored_flow_state_info[dpid]["dealSplitPktTime"])
            self.logger.info("Average Time of delay is :%d us\n================================", self.stored_flow_state_info[dpid]["averageDelay"])
        
        #quit this controller program
        #os._exit(0)


    @set_ev_cls(ofp_event.EventOFPErrorMsg,[HANDSHAKE_DISPATCHER, CONFIG_DISPATCHER, MAIN_DISPATCHER])
    def error_msg_handler(self, ev):
        msg = ev.msg
        self.logger.info('OFPErrorMsg received: type=0x%02x code=0x%02x '
                          'message=%s',
                          msg.type, msg.code, utils.hex_array(msg.data))
        if self.flowError == 0:
            self.logger.info("\n=================== FIRST ERROR ! ======================\n")
            self.flowError = 2 # 2 for aggregate flow stat measure, 1 for per flow
            self.flowAddEndTime = time.time()
            #self.flowAddAverageTime = (self.flowAddEndTime - self.flowAddStartTime) * 1000 * 1000 / 512
            #self.logger.info("\n=== AVERAGE FLOW ADD TIME IS : %s us ====\n",self.flowAddAverageTime)
            #datapath = None
            for id in self.datapaths:
                datapath = self.datapaths[id] #can only be used with 1 switch because error msg does not contain a datapath id, if there is multiple switch, it will be difficult to know the error from
                break;
            #self.send_aggregate_stats_request(self.datapaths[291])


    def send_port_stats_request(self, datapath):
        ofp = datapath.ofproto
        ofp_parser = datapath.ofproto_parser
        
        req = ofp_parser.OFPPortStatsRequest(datapath, 0, ofp.OFPP_ANY)
        datapath.send_msg(req)
        self.portRequestTime[datapath.id]= time.time() # when this round request start for each dp
        self.portStatCount[datapath.id] += 1 # how many rounds for each dp
        


    @set_ev_cls(ofp_event.EventOFPPortStatsReply, MAIN_DISPATCHER)
    def port_stats_reply_handler(self, ev):
        no_display_time = time.time()
        dpid = ev.msg.datapath.id
        #ports = []
        #self.logger.info("==================== DP %s, Round %s =====================",dpid,self.portStatCount[dpid])
        for stat in ev.msg.body:
            port_no = stat.port_no
            if port_no == 4294967294:
                port_no = 999
            data = {}
            data["rx_bytes"]=stat.rx_bytes
            data["tx_bytes"]=stat.tx_bytes
            data["total_bytes"]=int(stat.rx_bytes)+int(stat.tx_bytes)
            if not self.portStats[dpid].has_key(port_no):
                self.portStats[dpid][port_no]={}
                self.portStats[dpid][port_no]["total_bytes"]=0
                self.portStats[dpid][port_no]["duration"]=0
                
            data["trafficload"] = data["total_bytes"] - self.portStats[dpid][port_no]["total_bytes"]            
            data["duration"]=stat.duration_sec + stat.duration_nsec / 1000000000.0 + 0.0000001
            interval=data["duration"] - self.portStats[dpid][port_no]["duration"]
            data["bandwidthCost"] = data["trafficload"] * 1.0 / interval #in Bps
            #self.logger.info("Datapath-id %s Port-No %s | receive bytes: %s, send bytes: %s, total_bytes: %s",dpid, port_no,data["rx_bytes"],data["tx_bytes"],data["total_bytes"])
            #self.logger.info("new bytes: %s, time interval: %s, bandwidthCost: %s Bps",data["trafficload"],interval,data["bandwidthCost"])
            self.portStats[dpid][port_no] = data
            #ports.append('port_no=%d '
                         #'rx_packets=%d tx_packets=%d '
                         #'rx_bytes=%d tx_bytes=%d '
                         #'rx_dropped=%d tx_dropped=%d '
                         #'rx_errors=%d tx_errors=%d '
                         #'rx_frame_err=%d rx_over_err=%d rx_crc_err=%d '
                         #'collisions=%d duration_sec=%d duration_nsec=%d' %
                         #(stat.port_no,
                          #stat.rx_packets, stat.tx_packets,
                          #stat.rx_bytes, stat.tx_bytes,
                          #stat.rx_dropped, stat.tx_dropped,
                          #stat.rx_errors, stat.tx_errors,
                          #stat.rx_frame_err, stat.rx_over_err,
                          #stat.rx_crc_err, stat.collisions,
                          #stat.duration_sec, stat.duration_nsec))
        #self.logger.info('PortStats: %s\n', ports)
        #self.logger.info("==================== Done! =====================")
        display_time = time.time()
        roundtimeNoDisp = no_display_time - self.portRequestTime[dpid]
        controllerTimeCost = display_time - no_display_time
        self.portStatTotalTime[dpid] += roundtimeNoDisp
        self.portStatTotalControllerCost[dpid] += controllerTimeCost
        self.portStatAverageTime[dpid] = self.portStatTotalTime[dpid] * 1.0 / self.portStatCount[dpid] * 1000 * 1000 # in us
        self.portStatAverageControllerCost[dpid] = self.portStatTotalControllerCost[dpid] * 1.0 / self.portStatCount[dpid] * 1000 * 1000 # in us
        self.logger.info("Round:%d, this round time: %s us, controller time: %s us",self.portStatCount[dpid], roundtimeNoDisp * 1000 * 1000, controllerTimeCost * 1000 * 1000)
        self.logger.info("Average round time: %s us, average controller cost time: %s us\n\n",self.portStatAverageTime[dpid],self.portStatAverageControllerCost[dpid])

