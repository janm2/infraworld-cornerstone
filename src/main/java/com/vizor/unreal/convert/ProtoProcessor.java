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

import com.squareup.wire.schema.internal.parser.EnumConstantElement;
import com.squareup.wire.schema.internal.parser.EnumElement;
import com.squareup.wire.schema.internal.parser.FieldElement;
import com.squareup.wire.schema.internal.parser.MessageElement;
import com.squareup.wire.schema.internal.parser.ProtoFileElement;
import com.squareup.wire.schema.internal.parser.ServiceElement;
import com.squareup.wire.schema.internal.parser.TypeElement;
import com.sun.tools.sjavac.Log;
import com.squareup.wire.schema.internal.parser.OneOfElement;
import com.vizor.unreal.config.Config;
import com.vizor.unreal.config.DestinationConfig;
import com.vizor.unreal.provider.ProtoTypesProvider;
import com.vizor.unreal.provider.TypesProvider;
import com.vizor.unreal.provider.UnrealTypesProvider;
import com.vizor.unreal.tree.CppAnnotation;
import com.vizor.unreal.tree.CppClass;
import com.vizor.unreal.tree.CppDelegate;
import com.vizor.unreal.tree.CppEnum;
import com.vizor.unreal.tree.CppField;
import com.vizor.unreal.tree.CppNamespace;
import com.vizor.unreal.tree.CppRecord;
import com.vizor.unreal.tree.CppStruct;
import com.vizor.unreal.tree.CppType;
import com.vizor.unreal.tree.OneofItem;
import com.vizor.unreal.tree.preprocessor.CppInclude;
import com.vizor.unreal.util.MessageOrderResolver;
import com.vizor.unreal.util.Tuple;
import com.vizor.unreal.writer.CppPrinter;
import com.vizor.unreal.writer.CppPrinter.HeaderType;

import org.apache.logging.log4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.print.DocFlavor.STRING;

import static com.squareup.wire.schema.Field.Label.REPEATED;
import static com.vizor.unreal.tree.CppAnnotation.BlueprintReadWrite;
import static com.vizor.unreal.tree.CppAnnotation.BlueprintType;
import static com.vizor.unreal.tree.CppAnnotation.DisplayName;
import static com.vizor.unreal.tree.CppAnnotation.Transient;
import static com.vizor.unreal.tree.CppAnnotation.Category;
import static com.vizor.unreal.tree.CppRecord.Residence.Cpp;
import static com.vizor.unreal.tree.CppRecord.Residence.Header;
import static com.vizor.unreal.tree.CppType.Kind.Enum;
import static com.vizor.unreal.tree.CppType.Kind.Struct;
import static com.vizor.unreal.tree.CppType.plain;
import static com.vizor.unreal.util.Misc.reorder;
import static com.vizor.unreal.util.Misc.snakeCaseToCamelCase;
import static com.vizor.unreal.util.Misc.stringIsNullOrEmpty;
import static com.vizor.unreal.util.Tuple.of;
import static java.lang.String.join;
import static java.nio.file.Paths.get;
import static java.util.Arrays.asList;
import static java.util.Objects.nonNull;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;
import static org.apache.commons.io.FilenameUtils.removeExtension;
import static org.apache.logging.log4j.LogManager.getLogger;

class ProtoProcessorArgs
{
    ProtoProcessorArgs(final ProtoFileElement parse, final Path pathToProto,
    final DestinationConfig pathToConverted2, final String moduleName)
    {
        this.parse = requireNonNull(parse);
        this.pathToProto = requireNonNull(pathToProto);
        this.pathToConverted = requireNonNull(pathToConverted2);
        this.moduleName = requireNonNull(moduleName);

        this.wrapperName = removeExtension(pathToProto.toFile().getName());

        this.className = snakeCaseToCamelCase(wrapperName);

//        if (parse.packageName() == null)
//            throw new RuntimeException("package filed in proto file is required for cornerstone");

        this.packageNamespace = new CppNamespace(parse.packageName());
    }

    final ProtoFileElement parse;
    final Path pathToProto;
    final DestinationConfig pathToConverted;
    final String moduleName;

    final String wrapperName;

    final String className;
    final CppNamespace packageNamespace;
}

class ProtoProcessor implements Runnable
{
    private static final Logger log = getLogger(ProtoProcessor.class);
    private final Config config = Config.get();

    private final ProtoProcessorArgs args;

    private final TypesProvider ueProvider = new UnrealTypesProvider();
    private final TypesProvider protoProvider = new ProtoTypesProvider();

    private final List<ProtoProcessorArgs> otherProcessorArgs;

    // to avoid any confusion when converting code on Windows,
    // force part separator to be forward slash
    private static final String pathSeparator = "/";

    ProtoProcessor(ProtoProcessorArgs args, List<ProtoProcessorArgs> otherProcessorArgs) {
        this.args = args;
        this.otherProcessorArgs = otherProcessorArgs;
    }
    
    private Stream<ProtoProcessorArgs> GatherImportedProtos(final ProtoProcessorArgs proto, final List<ProtoProcessorArgs> otherProtos)
    {
        return otherProtos.stream()
            .filter(otherProto -> proto.parse.imports().stream().map(importPathString -> get(importPathString)).anyMatch(importPath -> importPath.equals(otherProto.pathToProto)));
    }

    private Stream<ProtoProcessorArgs> GatherImportedProtosDeep(final ProtoProcessorArgs proto, final List<ProtoProcessorArgs> otherProtos)
    {
        final Stream<ProtoProcessorArgs> importedProtos = GatherImportedProtos(proto, otherProtos);
        
        final List<ProtoProcessorArgs> argss = importedProtos.collect(Collectors.toList());

        return Stream.concat(Stream.of(proto), argss.stream().flatMap(importedProto->GatherImportedProtosDeep(importedProto, otherProtos))).distinct();
    }

    private void GatherTypes(final ProtoProcessorArgs proto, final List<ProtoProcessorArgs> otherProtos, TypesProvider ueProvider, TypesProvider protoProvider)
    {
        final Stream<ProtoProcessorArgs> importedProtos = GatherImportedProtosDeep(proto, otherProtos);

        importedProtos.forEach(
            importedProto -> importedProto.parse.types().forEach(
                typeElement ->
                {
                    final String fullTypeName = importedProto.parse.packageName() + "." + typeElement.name();

                    ueProvider.register(fullTypeName, ueNamedType(importedProto.className, typeElement));
                    protoProvider.register(fullTypeName, cppNamedType(importedProto.packageNamespace, typeElement));
                }
            )
        );
    }

    @Override
    public void run()
    {
        final List<ServiceElement> services = args.parse.services();

        GatherTypes(args, otherProcessorArgs, ueProvider, protoProvider);
        

        final List<Tuple<CppStruct, CppStruct>> castAssociations = new ArrayList<>();
        final List<CppStruct> unrealStructures = new ArrayList<>();

        final List<CppEnum> ueEnums = new ArrayList<>();

        // At this moment, we have all types registered in both type providers
        for (final TypeElement s : args.parse.types())
        {
            if (s instanceof MessageElement)
            {
                final MessageElement messageElement = (MessageElement) s;

                final CppStruct ueStruct = extractStruct(ueProvider, messageElement);
                final CppStruct protoStruct = extractStruct(protoProvider, messageElement);

                log.debug("Found type cast {} -> {}", ueStruct.getType(), protoStruct.getType());

                castAssociations.add(of(protoStruct, ueStruct));
                unrealStructures.add(ueStruct);
            }
            else if (s instanceof EnumElement)
            {
                ueEnums.add(extractEnum(ueProvider, (EnumElement) s));
            }
            else
            {
                throw new RuntimeException("Unknown type: '" + s.getClass().getName() + "'");
            }
        }

        // Topologically sort structures
        final MessageOrderResolver resolver = new MessageOrderResolver();
        
        {
	        final int[] indices = resolver.sortByInclusion(unrealStructures);
	
	        // Then reorder data types
	        reorder(unrealStructures, indices);
	        reorder(castAssociations, indices);
        }

        final CppNamespace casts = new CastGenerator().genCasts(castAssociations);
        
        // consolidate structs to parent structs
        consolidateStructs(ueProvider, unrealStructures);
        {
        	// reorder one more time with the consolidated structs
        	final int[] indices = resolver.sortByInclusion(unrealStructures);
        	reorder(unrealStructures, indices);
        }

        log.debug("Found structures (sorted): {}", () ->
            unrealStructures.stream().map(s -> s.getType().getName()).collect(joining(", ", "[", "]")
        ));

        // Generate RPC workers
        final WorkerGenerator workerGenerator = (config.isServer())? 
        										new ServerWorkerGenerator(services, ueProvider, args.parse):
        										new ClientWorkerGenerator(services, ueProvider, args.parse);
        
        final List<CppClass> workers = workerGenerator.genClass();

        // Generate RPC clients/servers
        final List<CppClass> classes = new ArrayList<>(services.size());
        final List<CppDelegate> dispatchers = new ArrayList<>(services.size());

        for (int i = 0; i < services.size(); i++)
        {
            final ServiceElement service = services.get(i);
            final CppClass worker = workers.get(i);

            // Get class code generator for server or client
            final CodeGenerator cg = (config.isServer())? 
            						  new ServerGenerator(service, ueProvider, worker.getType()): 
            						  new ClientGenerator(service, ueProvider, worker.getType());
            
            classes.add(cg.genClass());
            dispatchers.addAll(cg.getDelegates());
        }

        final String pathToProtoStr = removeExtension(args.pathToProto.getFileName().toString());
        DestinationConfig dstPath = Config.get().getDstPath();

        // Should create an output directories if does not exit.
        @SuppressWarnings("unused")
        final boolean ignorePublic = dstPath.pathPublic.toFile().mkdirs();
        
        @SuppressWarnings("unused")
        final boolean ignorePrivate = dstPath.pathPrivate.toFile().mkdirs();

        final List<CppInclude> headerIncludes = new ArrayList<>(asList(
            // header
            new CppInclude(Header, "CoreMinimal.h"),
            new CppInclude(Header, "Worker/Conduit.h"),
            new CppInclude(Header, "Utility/GenUtils.h"),
            (config.isServer())? 
            new CppInclude(Header, "Server/RpcServer.h") :
            new CppInclude(Header, "Client/RpcClient.h")
        ));

        if(isHaveVariantField(unrealStructures))
        {
            headerIncludes.add(new CppInclude(Header, "Misc/TVariant.h"));
        }

        final List<String> importedProtoNames = GatherImportedProtos(args, otherProcessorArgs).map(
            importedProto -> {
                return getHeaderPath(importedProto);
            }
        ).collect(Collectors.toList());

        headerIncludes.addAll(importedProtoNames.stream().map(path->new CppInclude(Header, path + ".h")).collect(Collectors.toList()));

        if (classes.size() > 0 || unrealStructures.size() > 0 || ueEnums.size() > 0)
        {
            headerIncludes.add(
                new CppInclude(Header, args.className + ".generated.h")
            );
        }
        
        final Config config = Config.get();

        final String publicIncludePath = config.getIncludePath();
        
        // TODO: Fix paths
        
        final String generatedIncludeNamePublic = join("/", config.getWrappersPath(),
               pathToProtoStr).replace("\\", pathSeparator);
        
        final String generatedIncludeName =  publicIncludePath + "/" + generatedIncludeNamePublic;//, args.wrapperName);

        final String generatedHeaderPath = join("/", publicIncludePath, args.className);
                
        final String castIncludeName = generatedHeaderPath + "Casts.h";

        // code. mutable to allow
        final List<CppRecord> cppIncludes = new ArrayList<>(asList(
            new CppInclude(Cpp, generatedHeaderPath + ".h"),
            new CppInclude(Cpp, "Worker/RpcWorker.h"),
            new CppInclude(Cpp, (config.isServer())?  "Server/ServiceRpcWorker.h" : "Client/StubbedRpcWorker.h"),

            new CppInclude(Cpp, "Utility/GrpcIncludesBegin.h"),

            new CppInclude(Cpp, "grpc/support/log.h", true),
            new CppInclude(Cpp, "grpcpp/channel.h", true),
            new CppInclude(Cpp, "Credentials/CredentialsProvider.h", false),

            new CppInclude(Cpp, generatedIncludeName + ".pb.hpp", false),
            new CppInclude(Cpp, generatedIncludeName + ".grpc.pb.hpp", false),

            new CppInclude(Cpp, "Utility/GrpcIncludesEnd.h"),
            new CppInclude(Cpp, castIncludeName)
        ));
        
        final List<CppRecord> castsIncludes = new ArrayList<>(asList(
            new CppInclude(Header, "Utility/CastUtils.h"),

            new CppInclude(Header, "Utility/GrpcIncludesBegin.h"),

            new CppInclude(Header, generatedIncludeNamePublic + ".pb.h", false),
            new CppInclude(Header, generatedIncludeNamePublic + ".grpc.pb.h", false),

            new CppInclude(Header, "Utility/GrpcIncludesEnd.h"),

            new CppInclude(Header, args.className + ".h")
        ));

        castsIncludes.addAll(
            importedProtoNames.stream()
            .map(path->new CppInclude(Header, path + "Casts.h"))
            .collect(Collectors.toList())
        );

        if (!stringIsNullOrEmpty(config.getPrecompiledHeader()))
            cppIncludes.add(0, new CppInclude(Cpp, config.getPrecompiledHeader(), false));

        final DestinationConfig outFilePath = dstPath.append(args.className);
        final DestinationConfig outCastsFilePath = dstPath.append(args.className + "Casts");
        
        try (final CppPrinter castsPrinter = new CppPrinter(outCastsFilePath, args.moduleName.toUpperCase(), HeaderType.Public))
        {
            castsIncludes.forEach(i -> i.accept(castsPrinter));
            castsPrinter.newLine();
    
            // Write casts to the CPP file
            casts.accept(castsPrinter).newLine();
        }

        try (final CppPrinter p = new CppPrinter(outFilePath, args.moduleName.toUpperCase()))
        {
            headerIncludes.forEach(i -> i.accept(p));
            p.newLine();

            cppIncludes.forEach(i -> i.accept(p));
            
            // add new line after includes to cpp
            p.code();
            p.newLine();
            p.header();

            // Write enums and structs
            if(!ueEnums.isEmpty())
            	p.writeInlineComment("Enums:");
            
            ueEnums.forEach(e -> e.accept(p).newLine());

            if(!unrealStructures.isEmpty())
            	p.writeInlineComment("Structures:");
            
            unrealStructures.forEach(s -> s.accept(p).newLine());

            p.writeInlineComment("Forward class definitions (for delegates)");
            classes.forEach(c -> p.write("class ").write(c.getType().toString()).writeLine(";"));
            p.newLine();

            p.writeInlineComment("Dispatcher delegates");
            dispatchers.forEach(d -> d.accept(p).newLine());
            p.newLine();

            // Write casts to the CPP file
            // casts.accept(p).newLine();

            // Workers are being written to the *.cpp file, have to write them before
            workers.forEach(c -> c.accept(p).newLine());

            classes.forEach(w -> w.accept(p).newLine());
        }
    }

    private static String getHeaderPath(final ProtoProcessorArgs args)
    {
        // remove extension and fix slashes up
        final String pathToGeneratedHeaderDirectory = removeExtension(args.pathToProto.toString()).replace("\\", pathSeparator);
                
        final String generatedHeaderPath = join(pathSeparator, pathToGeneratedHeaderDirectory, args.className);

        return generatedHeaderPath;
    }

    private CppStruct extractStruct(final TypesProvider provider, final MessageElement me)
    {
        final CppType type = provider.get(me.name());

        final List<CppField> fields = new ArrayList<>();
        for (final FieldElement fe : me.fields())
        {
            // Get the type
            final CppType ueType = provider.get(fe.type());

            // If the field is repeated - make a TArray<?> of type.
            final CppField field;
            if (fe.label() == REPEATED)
            {
                final CppType ueArrayType = provider.arrayOf(ueType);
                field = new CppField(ueArrayType, provider.fixFieldName(fe.name(), false));
            }
            else
            {
                final String fieldName = provider.fixFieldName(fe.name(), ueType.isA(boolean.class));
                field = new CppField(ueType, fieldName);
            }

            // Add docs if has any
            final String sourceDoc = fe.documentation();
            if (!sourceDoc.isEmpty())
                field.javaDoc.set(sourceDoc);

            field.addAnnotation(Category, "gRPC");
            field.addAnnotation(Transient);
            field.addAnnotation(BlueprintReadWrite);
            fields.add(field);
        }

        for(final OneOfElement onf : me.oneOfs())
        {
            final CppType ueType = provider.get("oneof");
            
            final String fieldName = provider.fixFieldName(onf.name(), ueType.isA(boolean.class));
            final CppField field = new CppField(ueType, fieldName);;
            
            log.debug("adding OneOfElement:");
            field.getSubTypes().addAll(onf.fields().stream().map(i ->
            {
            	OneofItem item = new OneofItem();
            	item.type = provider.get(i.type());
                log.debug("type:" + item.type.toString() );
                item.name = provider.fixFieldName(i.name(), ueType.isA(boolean.class));
                return item;
            }).collect(Collectors.toList()));
            

            final String sourceDoc = onf.documentation();
            if (!sourceDoc.isEmpty())
                field.javaDoc.set(sourceDoc);

            field.enableAnnotations(false);
            fields.add(field);

        }

        final CppStruct struct = new CppStruct(type, fields);

        struct.addAnnotation(DisplayName, args.className + " " + me.name());

        if (!me.documentation().isEmpty())
            struct.javaDoc.set(me.documentation());

        struct.addAnnotation(BlueprintType);

        struct.setResidence(Header);
        return struct;
    }

    private CppEnum extractEnum(final TypesProvider provider, final EnumElement ee)
    {
        final CppEnum cppEnum = new CppEnum(provider.get(ee.name()), ee.constants().stream()
                .collect(toMap(m -> provider.fixFieldName(m.name(), false), EnumConstantElement::tag)));

        if (!ee.documentation().isEmpty())
            cppEnum.getJavaDoc().set(ee.documentation());

        cppEnum.addAnnotation(BlueprintType);
        cppEnum.addAnnotation(DisplayName, args.className + " " + ee.name());

        cppEnum.setResidence(Header);
        return cppEnum;
    }

    private CppType ueNamedType(final String serviceName, final TypeElement el)
    {
        if (el instanceof MessageElement)
            return plain("F" + serviceName + "_" + el.name(), Struct);
        else if (el instanceof EnumElement)
            return plain("E" + serviceName + "_" + el.name(), Enum);
        else
            throw new RuntimeException("Unknown type: '" + el.getClass().getName() + "'");
    }



    private static CppType cppNamedType(CppNamespace packageNamespace, TypeElement el) {
        if (el instanceof MessageElement)
        {
            final CppType mt = plain(el.name(), Struct);

            if (packageNamespace.hasName())
                mt.setNamespaces(packageNamespace);

            return mt;
        }
        else if (el instanceof EnumElement)
        {
            final CppType et = plain(el.name(), Enum);

            if (packageNamespace.hasName())
                et.setNamespaces(packageNamespace);

            return et;
        }
        else
        {
            throw new RuntimeException("Unknown type: '" + el.getClass().getName() + "'");
        }
    }

    private CppType cppNamedType(final TypeElement el)
    {
        return cppNamedType(args.packageNamespace, el);
    }

    private boolean isHaveVariantField(List<CppStruct> unrealStructures)
    {
        for (CppStruct unrealStructure : unrealStructures)
        {
            for(CppField unreadField : unrealStructure.getFields())
            {
                if(unreadField.isVariant())
                {
                    return true;
                }
            }
        }
        return false;
    }
    
    private boolean isDisallowedTypeForConsolidate(String name)
    {
    	return name.equals("TArray") ||
    		   name.equals("TVariant") || 
    		   name.equals("TMap") ||
    		   name.equals("FByteArray");
    }
    
    private void consolidateStructs(final TypesProvider provider, List<CppStruct> structs)
    {
    	//List<StructPair> structPairs = new ArrayList<>();
    	
    	List<CppStruct> consolidatedStructs = new ArrayList<>();
    	
    	// get same struct fields
    	for(CppStruct struct : structs)
    	{
    		 //log.info("struct {}", struct.getType().getName());
    		 List<CppField> fields = struct.getFields();
    		 
    		for(CppStruct comperedStruct : structs)
    	    {
    			if(struct.getType().getName() == comperedStruct.getType().getName())
    			{
    				continue;
    			}
    			
    			List<CppField> comperedfields = comperedStruct.getFields();
    			
    			List<CppField> same = new ArrayList<>();
    			
	    		for(CppField field : fields)
	    	    {
	    			for(CppField comperedfield : comperedfields)
	 	    	    {
	    				 if(
	    					field.getType() == comperedfield.getType() &&
	    					field.getName().equals(comperedfield.getName()) &&
	    					!isDisallowedTypeForConsolidate(field.getType().getName())
	    				   )
	    				 {
	    					 same.add(field);
	    				 }
	 	    	    }
	    	    }
	    		
	    		if(same.size() > 0)
	    		{
	    			
	    			
	    			String name = "F";
	    			for(CppField sameField : same)
	    			{
	    				name += sameField.getName();
	    			}
	    			name += "Data";
	    			
	    			boolean found = false;
	    			for(CppStruct cosolidated : consolidatedStructs)
	    			{
	    				if(cosolidated.getType().getName().equals(name))
	    				{
	    					found = true;
	    					break;
	    				}
	    			}
	    			
	    			if(found)
	    			{
	    				continue;
	    			}
	    			
	    			final CppType type = plain(name, Struct);
	    			
	    			final CppStruct consolidatedStruct = new CppStruct(type, same);
	    			
	    			consolidatedStruct.addAnnotation(DisplayName, args.className + " " + name);
	    			consolidatedStruct.addAnnotation(BlueprintType);
	    			consolidatedStruct.setResidence(Header);
	    			
	    			consolidatedStructs.add(consolidatedStruct);
	    		}
	    		
    	    }
    	}
    	
    	// assign parents to the new sturcts
    	for(CppStruct struct : structs)
    	{
    		List<CppField> fields = struct.getFields();
    		
    		CppStruct bestFit = null; 
    		int bestFitsame = 0;
    		
	    	for(CppStruct consolidatedStruct  : consolidatedStructs)
	    	{
	    		int same = 0;
	    		
	    		for(CppField field : fields)
	    	    {
	    			for(CppField comperedfield : consolidatedStruct.getFields())
	 	    	    {
	    				 if(
	    					field.getType() == comperedfield.getType() &&
	    					field.getName().equals(comperedfield.getName()) &&
	    					!isDisallowedTypeForConsolidate(field.getType().getName())
	    				   )
	    				 {
	    					 same++;
	    				 }
	 	    	    }
	    	    }
	    		
	    		
	    		if(bestFitsame < same)
	    		{
	    			bestFit = consolidatedStruct;
	    			bestFitsame = same;
	    		}
	    	}
	    	
	    	if(nonNull(bestFit))
	    	{
	    		struct.removeFields(bestFit.getFields());
	    		struct.SetPerent(bestFit);
	    	}
    	}
    	
    	structs.addAll(0, consolidatedStructs);
    }
}
