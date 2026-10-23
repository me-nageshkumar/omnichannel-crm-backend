package me.nageshk.omnichannel.crm.field;

/** The kinds of value a custom field holds; each has its own validation in {@link FieldValidator}. */
public enum FieldType { TEXT, LONG_TEXT, NUMBER, CURRENCY, BOOLEAN, DATE, DATETIME, SELECT, MULTI_SELECT, URL, EMAIL, PHONE, USER }
