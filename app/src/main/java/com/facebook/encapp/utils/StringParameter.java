package com.facebook.encapp.utils;

import com.facebook.encapp.proto.DataValueType;
import com.facebook.encapp.proto.Parameter;

// The protobuf is using a factory way of constructing the objects. One way of solving creating of
// parameters would be to use a c protobuf implementation. For now we will use a simpler version where
// we create a simple object with only strings and convert in java instead.
public class StringParameter {
    String mKey = "";
    String mType = "";
    String mValue = "";

    public StringParameter(String key, String type, String value) {
        mKey = key;
        mType = type;
        mValue = value;
    }

    public Parameter getParameter() {
        if (mType.equals(DataValueType.intType.name())) {
            return Parameter.newBuilder().setType(DataValueType.intType).setKey(mKey).setValue(mValue).build();
        } else if (mType.equals(DataValueType.longType.name())) {
            return Parameter.newBuilder().setType(DataValueType.longType).setKey(mKey).setValue(mValue).build();
        } else if (mType.equals(DataValueType.floatType.name())) {
            return Parameter.newBuilder().setType(DataValueType.floatType).setKey(mKey).setValue(mValue).build();
        } else if (mType.equals(DataValueType.longType.name())) {
            return Parameter.newBuilder().setType(DataValueType.stringType).setKey(mKey).setValue(mValue).build();
        }

        // Oh no!
        return null;
    }
}
