package xipeng.statcontroller;

import java.util.Map;
import java.util.List;

import org.projectfloodlight.openflow.protocol.OFAggregateStatsReply;
import org.projectfloodlight.openflow.protocol.OFFlowStatsReply;
import org.projectfloodlight.openflow.types.DatapathId;

import net.floodlightcontroller.core.module.IFloodlightService;


public interface IFlowStatCollectionService extends IFloodlightService {
	public List<OFFlowStatsReply> getFlowStatDesc(DatapathId dpid);
	
	public OFAggregateStatsReply getAggrStatDesc(DatapathId dpid);
	
	public Map<String, Double> getStatTime(DatapathId dpid);
	
	public void collectStatistics(boolean collect);

}
