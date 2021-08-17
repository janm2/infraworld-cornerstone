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
package com.vizor.unreal.convert;

import com.squareup.wire.schema.internal.parser.RpcElement;
import com.squareup.wire.schema.internal.parser.ServiceElement;
import com.vizor.unreal.config.Config;
import com.vizor.unreal.provider.TypesProvider;
import com.vizor.unreal.tree.CppArgument;
import com.vizor.unreal.tree.CppClass;
import com.vizor.unreal.tree.CppDelegate;
import com.vizor.unreal.tree.CppField;
import com.vizor.unreal.tree.CppFunction;
import com.vizor.unreal.tree.CppType;
import com.vizor.unreal.util.Tuple;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.vizor.unreal.tree.CppAnnotation.BlueprintAssignable;
import static com.vizor.unreal.tree.CppAnnotation.BlueprintCallable;
import static com.vizor.unreal.tree.CppAnnotation.Category;
import static com.vizor.unreal.tree.CppType.Kind.Class;
import static com.vizor.unreal.tree.CppType.Kind.Struct;
import static com.vizor.unreal.tree.CppType.plain;
import static com.vizor.unreal.tree.CppType.wildcardGeneric;
import static java.lang.String.join;
import static java.lang.System.lineSeparator;
import static java.text.MessageFormat.format;
import static java.util.Arrays.asList;
import static java.util.stream.Collectors.toList;

public class ClientGenerator extends CodeGenerator
{

    // Bulk cache
    private final List<Tuple<CppDelegate, CppField>> delegates;

    ClientGenerator(final ServiceElement service, final TypesProvider provider, final CppType workerType) 
    {
    	super(service, provider, workerType);
    	
        delegates = genDelegates();
        conduits = genConduits(reqWithCtx, rspWithSts);
    }

    @Override
    public String getClassName()
    {
    	return "RpcClient";
    }
    
    @Override
    public CppClass genClass()
    {
        final List<CppFunction> methods = new ArrayList<>();
        methods.add(genInitialize());
        methods.add(genUpdate());
        methods.addAll(genProcedures());

        final List<CppField> fields = new ArrayList<>(conduits);
        fields.addAll(delegates.stream().map(Tuple::second).collect(toList()));

        return new CppClass(dispatcherType, parentType, fields, methods);
    }

    @Override
    public List<CppDelegate> getDelegates()
    {
        return delegates.stream().map(Tuple::first).collect(toList());
    }

    private List<Tuple<CppDelegate, CppField>> genDelegates()
    {
        // two named arguments
        final CppArgument dispatcherArg = new CppArgument(dispatcherType.makePtr(), getClassName());
        final CppArgument statusArg = new CppArgument(plain("FGrpcStatus", Struct), "Status");

        
        List<Tuple<CppDelegate, CppField>> delegates = new ArrayList<>();
    	
    	final List<RpcElement> rpcs = service.rpcs();
    	 
    	rpcs.forEach( r -> {
    		final CppType rsp = provider.get(r.responseType());
    		
    		final CppArgument responseArg = new CppArgument(rsp.makeRef(), "Response");
    		final CppType eventType = plain(eventTypePrefix + r.name() + service.name(), Struct);
    		
    		
    		delegates.add( Tuple.of(
				    				new CppDelegate(eventType, asList(dispatcherArg, responseArg, statusArg)),
				    				new CppField(eventType, eventPrefix + r.name())
		    					   )
    					 );
    	});
 
        return delegates;
    }

    private CppFunction genInitialize()
    {
        final StringBuilder sb = new StringBuilder(supressSuperString(initFunctionName));
        final String cName = workerType.getName();

        final String workerVariableName = "Worker";

        sb.append(cName).append("* const ").append(workerVariableName).append(" = new ").append(cName).append("();");
        sb.append(lineSeparator()).append(lineSeparator());

        conduits.forEach(f -> {
            sb.append(workerVariableName).append("->").append(f.getName()).append(" = &");

            sb.append(f.getName()).append(';').append(lineSeparator());
            sb.append(f.getName()).append('.').append("AcquireRequestsProducer();");

            sb.append(lineSeparator()).append(lineSeparator());
        });

        sb.append("InnerWorker = TUniquePtr<RpcWorker>(").append(workerVariableName).append(");");
        sb.append(lineSeparator()).append(lineSeparator());

        final CppFunction init = new CppFunction(initFunctionName, voidType);

        init.isOverride = true;
        init.setBody(sb.toString());
        init.enableAnnotations(false);

        return init;
    }

    private CppFunction genUpdate()
    {
        final String dequeuePattern = join(lineSeparator(), asList(
            "if (!{0}.IsEmpty())",
            "'{'",
            "    {1} ResponseWithStatus;",
            "    while ({0}.Dequeue(ResponseWithStatus))",
            "        {2}.Broadcast(",
            "            this,",
            "            ResponseWithStatus.Response,",
            "            ResponseWithStatus.Status",
            "        );",
            "'}'"
        ));

        final StringBuilder sb = new StringBuilder(supressSuperString(updateFunctionName));
        for (int i = 0; i < conduits.size(); i++)
        {
            final Tuple<CppDelegate, CppField> delegate = delegates.get(i);
            final CppField conduit = conduits.get(i);

            final String dequeue = delegate.reduce((d, f) -> {
                final List<CppType> genericParams = conduit.getType().getGenericParams();
                final CppType requestWithContext = genericParams.get(1);

                return format(dequeuePattern, conduit.getName(), requestWithContext.toString(), f.getName());
            });

            sb.append(dequeue).append(lineSeparator()).append(lineSeparator());
        }

        final CppFunction update = new CppFunction(updateFunctionName, voidType);
        update.isOverride = true;
        update.enableAnnotations(false);
        update.setBody(sb.toString());

        return update;
    }

    private List<CppFunction> genProcedures()
    {
        final String pattern = join(lineSeparator(), asList(
            "if (!HasStarted())",
            "    return false;",
            "",
            "{0}Conduit.Enqueue(TRequestWithContext$New(Request, Context));",
            "return true;"
        ));

        final CppArgument contextArg = new CppArgument(plain("FGrpcClientContext", Struct).makeRef(), "Context");

        List<CppFunction> methods = new ArrayList<>();
    	
    	final List<RpcElement> rpcs = service.rpcs();
        
    	
    	rpcs.forEach( r -> {
            	
        		final CppType req = provider.get(r.requestType());
            	
                final CppArgument requestArg = new CppArgument(req, "Request");
                final CppFunction method = new CppFunction(r.name(), boolType, asList(requestArg, contextArg));

                method.setBody(format(pattern, r.name()));
                method.addAnnotation(BlueprintCallable);
                method.addAnnotation(Category, rpcRequestsCategory + service.name());

                methods.add(method);
            });
        
        return methods;
    }
}
