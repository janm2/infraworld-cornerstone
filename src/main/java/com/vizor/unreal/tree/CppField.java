/*
 * Copyright 2018 Vizor Games LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.vizor.unreal.tree;

import com.vizor.unreal.writer.CppPrinter;

import java.util.List;
import java.util.ArrayList;

public class CppField extends CppRecord
{
    private final CppType type;
    private final String name;
    private final List<OneofItem> SubTypes; // only for oneOf

    public final CppJavaDoc javaDoc = new CppJavaDoc();

    public CppField(final CppType type, final String name)
    {
        this.type = type;
        this.name = name;
        this.SubTypes = new ArrayList<>();
    }

    public CppType getType()
    {
        return type;
    }

    public String getName()
    {
        return name;
    }
    
    public boolean isVariant()
    {
    	return !SubTypes.isEmpty();
    }
    
    public List<OneofItem> getSubTypes()
    {
    	return SubTypes;
    }

    @Override
    public CppPrinter accept(final CppPrinter printer)
    {
        printer.visit(this);
        return printer;
    }
}
