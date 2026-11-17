package me.nageshk.omnichannel.db.billing;

/** Only an ACTIVE plan can be given to a customer; a RETIRED one stays on those that have it. */
public enum PlanStatus { DRAFT, ACTIVE, RETIRED }
