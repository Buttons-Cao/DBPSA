package org.apache.hadoop.yarn.api.records;

import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.yarn.util.Records;

/**
 * The PreemptionPriority of a container is to be considered when selecting a container to execute preemption event.
 * <p>
 * Created by deqianzou on 2017/12/25.
 */
public abstract class PreemptionPriority implements Comparable<PreemptionPriority> {

	public static final PreemptionPriority UNDEFINED = newInstance(-1);

	@InterfaceAudience.Public
	@InterfaceStability.Stable
	public static PreemptionPriority newInstance(float p) {
		PreemptionPriority preemptionPriority = Records.newRecord(PreemptionPriority.class);
		preemptionPriority.setPreemptionPriority(p);
		return preemptionPriority;
	}

	/**
	 * Get the preemption priority.
	 *
	 * @return the preemption priority
	 */
	@InterfaceAudience.Public
	@InterfaceStability.Stable
	public abstract int getPreemptionPriority();

	/**
	 * Set the preemption priority
	 *
	 * @param preemptionPriority the new preemption priority
	 */
	@InterfaceAudience.Public
	@InterfaceStability.Stable
	public abstract void setPreemptionPriority(float preemptionPriority);

	@Override
	public int hashCode() {
		final int prime = 517861;
		int result = 9511;
		result = prime * result + getPreemptionPriority();
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		Priority other = (Priority) obj;
		if (getPreemptionPriority() != other.getPriority()) {
			return false;
		}
		return true;
	}

	@Override
	public int compareTo(PreemptionPriority other) {
		return other.getPreemptionPriority() - this.getPreemptionPriority();
	}

	@Override
	public String toString() {
		return "{PreemptionPriority: " + getPreemptionPriority() + "}";
	}
}
