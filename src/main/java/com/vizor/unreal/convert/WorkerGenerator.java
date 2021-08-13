package com.vizor.unreal.convert;

import static com.vizor.unreal.convert.ClientGenerator.conduitName;
import static com.vizor.unreal.convert.ClientGenerator.conduitType;
import static com.vizor.unreal.convert.ClientGenerator.reqWithCtx;
import static com.vizor.unreal.convert.ClientGenerator.rspWithSts;
import static com.vizor.unreal.tree.CppType.wildcardGeneric;
import static com.vizor.unreal.tree.CppType.Kind.Struct;
import static java.util.stream.Collectors.toList;

import java.util.List;

import com.squareup.wire.schema.internal.parser.ProtoFileElement;
import com.squareup.wire.schema.internal.parser.ServiceElement;
import com.vizor.unreal.provider.TypesProvider;
import com.vizor.unreal.tree.CppClass;
import com.vizor.unreal.tree.CppField;
import com.vizor.unreal.tree.CppNamespace;
import com.vizor.unreal.tree.CppType;

public abstract class WorkerGenerator 
{
	protected static final CppType wildcardUniquePtr = wildcardGeneric("unique_ptr", Struct, 1);
	
    static
    {
        wildcardUniquePtr.setNamespaces(new CppNamespace("std"));
    }
	
	protected final List<ServiceElement> services;
	protected final TypesProvider provider;
	protected final ProtoFileElement parse;
	protected final CppType voidType;
	protected final CppType boolType;

	WorkerGenerator(List<ServiceElement> services, TypesProvider provider, ProtoFileElement parse)
    {
        this.services = services;
        this.provider = provider;
        this.voidType = provider.getNative(void.class);
        this.boolType = provider.getNative(boolean.class);
        this.parse = parse;
    }
	
	public abstract List<CppClass> genClass();
	
	protected String getPackageNamespaceString()
    {
        return parse.packageName() != null ? parse.packageName() + "::" : "";
    }
	
	protected List<CppField> extractConduits(final ServiceElement service, CppType reqType, CppType resType)
    {
        return service.rpcs().stream()
            .map(rpc -> {
                // Extract conduits (bidirectional queues)
                final CppType compiledGenericConduit = conduitType.makeGeneric(
                		reqType.makeGeneric(provider.get(rpc.requestType())),
                		resType.makeGeneric(provider.get(rpc.responseType()))
                );

                final CppField conduit = new CppField(compiledGenericConduit.makePtr(), rpc.name() + conduitName);
                conduit.enableAnnotations(false);

                return conduit;
            })
            .collect(toList());
    }
}
