package com.lsmom.core.work;

/** 约束 Profile。 */
public record ConstraintProfile(String code, String name, ConflictMode conflictMode, boolean outputUnitRequired,
                                boolean materialRequired, boolean verifySeparation) {

    public enum ConflictMode { WARN, BLOCK }

    public static final String DEFAULT = "LOOSE";
}
