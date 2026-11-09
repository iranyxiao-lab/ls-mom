package com.lsmom.action.api;

/** 幂等策略：REQUIRED 必须带幂等键。 */
public enum IdempotencyPolicy { NONE, OPTIONAL, REQUIRED }
