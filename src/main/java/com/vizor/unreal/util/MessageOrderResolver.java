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
package com.vizor.unreal.util;

//import com.vizor.unreal.convert.ProtoProcessor;
import com.vizor.unreal.tree.CppField;
import com.vizor.unreal.tree.CppStruct;
import com.vizor.unreal.tree.CppType;
import com.vizor.unreal.util.Graph.GraphHasCyclesException;

import static org.apache.logging.log4j.LogManager.getLogger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Objects.nonNull;

import org.apache.logging.log4j.Logger;

public class MessageOrderResolver
{
	private static final Logger log = getLogger(MessageOrderResolver.class);
	
    public int[] sortByInclusion(final List<CppStruct> structures)
    {
        final Set<CppType> cache = new HashSet<>(structures.size());
        final List<CppType> list = new ArrayList<>(structures.size());

        structures.forEach(cppStruct -> {
            cache.add(cppStruct.getType());
            list.add(cppStruct.getType());
        });

        final Graph<CppType> graph = new Graph<>(list);
        for (final CppStruct struct : structures)
        {
        	log.debug("struct: " + struct.toString());
        	
            for (final CppField field : struct.getFields())
            {
            	
            	log.debug("field: " + field.getType().toString());
            	
                final CppType fieldType = field.getType();

                // If a field type has a reference to this struct type - add it as edge
                if (cache.contains(fieldType)) 
                {
                	log.debug("addEdge fieldType: " + fieldType + " -> "+ struct.getType() );
                    graph.addEdge(fieldType, struct.getType());
                }

                // It the field's type is generic class - perform the same inclusion check for all it's arguments
                // getFlatGenericArguments() returns an empty collection if it doesn't contain any generic arguments
                fieldType.getFlatGenericArguments().stream()
                    .filter(cache::contains)
                    .forEach(
                    		genericArg -> 
                    		{ 
                    			log.debug("addEdge genericArg: " + genericArg + " -> "+ struct.getType() );
                    			graph.addEdge(genericArg, struct.getType());
                    		}
                    		);

                field.getSubTypes().stream()
                        .forEach(
                        		variantArg -> 
                        		{
                        			 if (cache.contains(variantArg.type)) 
                                     {
                        				 log.debug("addEdge SubTypes: " + variantArg.type + " -> "+ struct.getType() );
                        				 graph.addEdge(variantArg.type, struct.getType());
                                     }
                        		}
                        		);
            }
            
            CppStruct perent = struct.getPerent();
			if (nonNull(perent) && cache.contains(perent.getType())) 
            {
				log.debug("addEdge SubTypes: " + perent.getType() + " -> "+ struct.getType() );
				graph.addEdge(perent.getType(),  struct.getType());
            }

        }

        try
        {
            return graph.getOrder();
        }
        catch (GraphHasCyclesException e)
        {
            throw new RuntimeException(e);
        }
    }
}
