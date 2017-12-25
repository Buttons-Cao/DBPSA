package org.apache.hadoop.yarn.api.records.impl.pb;

import org.apache.hadoop.yarn.api.records.PreemptionPriority;
import org.apache.hadoop.yarn.proto.YarnProtos.PriorityProto;
import org.apache.hadoop.yarn.proto.YarnProtos.PriorityProtoOrBuilder;

/**
 * Created by deqianzou on 2017/12/25.
 * @author deqianzou
 */
public class PreemptionPriorityPBImpl extends PreemptionPriority {

	PriorityProto proto = PriorityProto.getDefaultInstance();;
	PriorityProto.Builder builder = null;
	boolean viaProto = false;

	public PreemptionPriorityPBImpl(PriorityProto proto) {
		this.proto = proto;
		this.viaProto = true;
	}

	public PriorityProto getProto() {
		proto = viaProto ? proto : builder.build();
		viaProto = true;
		return proto;
	}

	private void maybeInitBuilder() {
		if (viaProto || builder == null) {
			builder = PriorityProto.newBuilder(proto);
		}
		viaProto = false;
	}

	@Override
	public int getPreemptionPriority() {
		PriorityProtoOrBuilder p = viaProto ? proto : builder;
		return (p.getPreemptionPriority());
	}

	@Override
	public void setPreemptionPriority(float preemptionPriority) {
		maybeInitBuilder();
		builder.setPreemptionPriority((preemptionPriority));
	}

	@Override
	public int compareTo(PreemptionPriority o) {
		return super.compareTo(o);
	}
}
