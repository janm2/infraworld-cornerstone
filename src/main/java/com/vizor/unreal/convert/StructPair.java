package com.vizor.unreal.convert;

import com.vizor.unreal.tree.CppStruct;
import java.util.List;

import com.vizor.unreal.tree.CppField;

public class StructPair {
	public CppStruct StructA;
	public CppStruct StructB;
	public List<CppField> SameFields;
	
	 public StructPair(final CppStruct structA, final CppStruct structB,final List<CppField> sameFields)
    {
        this.StructA = structA;
        this.StructB = structB;
        this.SameFields = sameFields;
    }
}
