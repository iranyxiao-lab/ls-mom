package com.lsmom.core.document;

/** 附件密级，按级别从低到高。 */
public enum Classification {
    PUBLIC, INTERNAL, CONFIDENTIAL, SECRET;

    public boolean allowedFor(Classification clearance) {
        return clearance.ordinal() >= ordinal();
    }
}
