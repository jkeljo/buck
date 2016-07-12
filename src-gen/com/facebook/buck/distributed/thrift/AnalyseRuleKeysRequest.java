/**
 * Autogenerated by Thrift Compiler (0.9.3)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package com.facebook.buck.distributed.thrift;

import org.apache.thrift.scheme.IScheme;
import org.apache.thrift.scheme.SchemeFactory;
import org.apache.thrift.scheme.StandardScheme;

import org.apache.thrift.scheme.TupleScheme;
import org.apache.thrift.protocol.TTupleProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.EncodingUtils;
import org.apache.thrift.TException;
import org.apache.thrift.async.AsyncMethodCallback;
import org.apache.thrift.server.AbstractNonblockingServer.*;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.Set;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.Collections;
import java.util.BitSet;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.annotation.Generated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked"})
@Generated(value = "Autogenerated by Thrift Compiler (0.9.3)", date = "2016-07-12")
public class AnalyseRuleKeysRequest implements org.apache.thrift.TBase<AnalyseRuleKeysRequest, AnalyseRuleKeysRequest._Fields>, java.io.Serializable, Cloneable, Comparable<AnalyseRuleKeysRequest> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("AnalyseRuleKeysRequest");

  private static final org.apache.thrift.protocol.TField RULE_KEYS_FIELD_DESC = new org.apache.thrift.protocol.TField("ruleKeys", org.apache.thrift.protocol.TType.LIST, (short)1);

  private static final Map<Class<? extends IScheme>, SchemeFactory> schemes = new HashMap<Class<? extends IScheme>, SchemeFactory>();
  static {
    schemes.put(StandardScheme.class, new AnalyseRuleKeysRequestStandardSchemeFactory());
    schemes.put(TupleScheme.class, new AnalyseRuleKeysRequestTupleSchemeFactory());
  }

  public List<String> ruleKeys; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    RULE_KEYS((short)1, "ruleKeys");

    private static final Map<String, _Fields> byName = new HashMap<String, _Fields>();

    static {
      for (_Fields field : EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // RULE_KEYS
          return RULE_KEYS;
        default:
          return null;
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, throwing an exception
     * if it is not found.
     */
    public static _Fields findByThriftIdOrThrow(int fieldId) {
      _Fields fields = findByThriftId(fieldId);
      if (fields == null) throw new IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /**
     * Find the _Fields constant that matches name, or null if its not found.
     */
    public static _Fields findByName(String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final String _fieldName;

    _Fields(short thriftId, String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    public short getThriftFieldId() {
      return _thriftId;
    }

    public String getFieldName() {
      return _fieldName;
    }
  }

  // isset id assignments
  private static final _Fields optionals[] = {_Fields.RULE_KEYS};
  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.RULE_KEYS, new org.apache.thrift.meta_data.FieldMetaData("ruleKeys", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.ListMetaData(org.apache.thrift.protocol.TType.LIST, 
            new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING))));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(AnalyseRuleKeysRequest.class, metaDataMap);
  }

  public AnalyseRuleKeysRequest() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public AnalyseRuleKeysRequest(AnalyseRuleKeysRequest other) {
    if (other.isSetRuleKeys()) {
      List<String> __this__ruleKeys = new ArrayList<String>(other.ruleKeys);
      this.ruleKeys = __this__ruleKeys;
    }
  }

  public AnalyseRuleKeysRequest deepCopy() {
    return new AnalyseRuleKeysRequest(this);
  }

  @Override
  public void clear() {
    this.ruleKeys = null;
  }

  public int getRuleKeysSize() {
    return (this.ruleKeys == null) ? 0 : this.ruleKeys.size();
  }

  public java.util.Iterator<String> getRuleKeysIterator() {
    return (this.ruleKeys == null) ? null : this.ruleKeys.iterator();
  }

  public void addToRuleKeys(String elem) {
    if (this.ruleKeys == null) {
      this.ruleKeys = new ArrayList<String>();
    }
    this.ruleKeys.add(elem);
  }

  public List<String> getRuleKeys() {
    return this.ruleKeys;
  }

  public AnalyseRuleKeysRequest setRuleKeys(List<String> ruleKeys) {
    this.ruleKeys = ruleKeys;
    return this;
  }

  public void unsetRuleKeys() {
    this.ruleKeys = null;
  }

  /** Returns true if field ruleKeys is set (has been assigned a value) and false otherwise */
  public boolean isSetRuleKeys() {
    return this.ruleKeys != null;
  }

  public void setRuleKeysIsSet(boolean value) {
    if (!value) {
      this.ruleKeys = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case RULE_KEYS:
      if (value == null) {
        unsetRuleKeys();
      } else {
        setRuleKeys((List<String>)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case RULE_KEYS:
      return getRuleKeys();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case RULE_KEYS:
      return isSetRuleKeys();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof AnalyseRuleKeysRequest)
      return this.equals((AnalyseRuleKeysRequest)that);
    return false;
  }

  public boolean equals(AnalyseRuleKeysRequest that) {
    if (that == null)
      return false;

    boolean this_present_ruleKeys = true && this.isSetRuleKeys();
    boolean that_present_ruleKeys = true && that.isSetRuleKeys();
    if (this_present_ruleKeys || that_present_ruleKeys) {
      if (!(this_present_ruleKeys && that_present_ruleKeys))
        return false;
      if (!this.ruleKeys.equals(that.ruleKeys))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    List<Object> list = new ArrayList<Object>();

    boolean present_ruleKeys = true && (isSetRuleKeys());
    list.add(present_ruleKeys);
    if (present_ruleKeys)
      list.add(ruleKeys);

    return list.hashCode();
  }

  @Override
  public int compareTo(AnalyseRuleKeysRequest other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = Boolean.valueOf(isSetRuleKeys()).compareTo(other.isSetRuleKeys());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetRuleKeys()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.ruleKeys, other.ruleKeys);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }

  public void read(org.apache.thrift.protocol.TProtocol iprot) throws org.apache.thrift.TException {
    schemes.get(iprot.getScheme()).getScheme().read(iprot, this);
  }

  public void write(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    schemes.get(oprot.getScheme()).getScheme().write(oprot, this);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("AnalyseRuleKeysRequest(");
    boolean first = true;

    if (isSetRuleKeys()) {
      sb.append("ruleKeys:");
      if (this.ruleKeys == null) {
        sb.append("null");
      } else {
        sb.append(this.ruleKeys);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // check for sub-struct validity
  }

  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    try {
      write(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(out)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
    try {
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class AnalyseRuleKeysRequestStandardSchemeFactory implements SchemeFactory {
    public AnalyseRuleKeysRequestStandardScheme getScheme() {
      return new AnalyseRuleKeysRequestStandardScheme();
    }
  }

  private static class AnalyseRuleKeysRequestStandardScheme extends StandardScheme<AnalyseRuleKeysRequest> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, AnalyseRuleKeysRequest struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // RULE_KEYS
            if (schemeField.type == org.apache.thrift.protocol.TType.LIST) {
              {
                org.apache.thrift.protocol.TList _list78 = iprot.readListBegin();
                struct.ruleKeys = new ArrayList<String>(_list78.size);
                String _elem79;
                for (int _i80 = 0; _i80 < _list78.size; ++_i80)
                {
                  _elem79 = iprot.readString();
                  struct.ruleKeys.add(_elem79);
                }
                iprot.readListEnd();
              }
              struct.setRuleKeysIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          default:
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
        }
        iprot.readFieldEnd();
      }
      iprot.readStructEnd();

      // check for required fields of primitive type, which can't be checked in the validate method
      struct.validate();
    }

    public void write(org.apache.thrift.protocol.TProtocol oprot, AnalyseRuleKeysRequest struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.ruleKeys != null) {
        if (struct.isSetRuleKeys()) {
          oprot.writeFieldBegin(RULE_KEYS_FIELD_DESC);
          {
            oprot.writeListBegin(new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.STRING, struct.ruleKeys.size()));
            for (String _iter81 : struct.ruleKeys)
            {
              oprot.writeString(_iter81);
            }
            oprot.writeListEnd();
          }
          oprot.writeFieldEnd();
        }
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class AnalyseRuleKeysRequestTupleSchemeFactory implements SchemeFactory {
    public AnalyseRuleKeysRequestTupleScheme getScheme() {
      return new AnalyseRuleKeysRequestTupleScheme();
    }
  }

  private static class AnalyseRuleKeysRequestTupleScheme extends TupleScheme<AnalyseRuleKeysRequest> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, AnalyseRuleKeysRequest struct) throws org.apache.thrift.TException {
      TTupleProtocol oprot = (TTupleProtocol) prot;
      BitSet optionals = new BitSet();
      if (struct.isSetRuleKeys()) {
        optionals.set(0);
      }
      oprot.writeBitSet(optionals, 1);
      if (struct.isSetRuleKeys()) {
        {
          oprot.writeI32(struct.ruleKeys.size());
          for (String _iter82 : struct.ruleKeys)
          {
            oprot.writeString(_iter82);
          }
        }
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, AnalyseRuleKeysRequest struct) throws org.apache.thrift.TException {
      TTupleProtocol iprot = (TTupleProtocol) prot;
      BitSet incoming = iprot.readBitSet(1);
      if (incoming.get(0)) {
        {
          org.apache.thrift.protocol.TList _list83 = new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.STRING, iprot.readI32());
          struct.ruleKeys = new ArrayList<String>(_list83.size);
          String _elem84;
          for (int _i85 = 0; _i85 < _list83.size; ++_i85)
          {
            _elem84 = iprot.readString();
            struct.ruleKeys.add(_elem84);
          }
        }
        struct.setRuleKeysIsSet(true);
      }
    }
  }

}
