package graphql.schema.diffing;

import graphql.execution.ValuesResolver;
import graphql.introspection.Introspection;
import graphql.language.AstPrinter;
import graphql.schema.*;
import graphql.schema.idl.DirectiveInfo;
import graphql.schema.idl.ScalarInfo;
import graphql.util.TraversalControl;
import graphql.util.Traverser;
import graphql.util.TraverserContext;
import graphql.util.TraverserVisitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static graphql.Assert.assertNotNull;

public class SchemaGraphFactory {

    public static final String ISOLATED = "__ISOLATED";

    public static final String DUMMY_TYPE_VERTEX = "__DUMMY_TYPE_VERTEX";
    public static final String OBJECT = "Object";
    public static final String INTERFACE = "Interface";
    public static final String UNION = "Union";
    public static final String INPUT_OBJECT = "InputObject";
    public static final String SCALAR = "Scalar";
    public static final String ENUM = "Enum";
    public static final String ENUM_VALUE = "EnumValue";
    public static final String APPLIED_DIRECTIVE = "AppliedDirective";
    public static final String FIELD = "Field";
    public static final String ARGUMENT = "Argument";
    public static final String APPLIED_ARGUMENT = "AppliedArgument";
    public static final String DIRECTIVE = "Directive";
    public static final String INPUT_FIELD = "InputField";

    public static final List<String> ALL_TYPES = Arrays.asList(DUMMY_TYPE_VERTEX, OBJECT, INTERFACE, UNION, INPUT_OBJECT, SCALAR, ENUM, ENUM_VALUE, APPLIED_DIRECTIVE, FIELD, ARGUMENT, APPLIED_ARGUMENT, DIRECTIVE, INPUT_FIELD);
    public static final List<String> ALL_NAMED_TYPES = Arrays.asList(OBJECT, INTERFACE, UNION, INPUT_OBJECT, SCALAR, ENUM);

    private int counter = 1;
    private final String debugPrefix;

    public SchemaGraphFactory(String debugPrefix) {
        this.debugPrefix = debugPrefix;
    }

    public SchemaGraphFactory() {
        this.debugPrefix = "";
    }

    public SchemaGraph createGraph(GraphQLSchema schema) {
        Set<GraphQLSchemaElement> roots = new LinkedHashSet<>();
        roots.add(schema.getQueryType());
        if (schema.isSupportingMutations()) {
            roots.add(schema.getMutationType());
        }
        if (schema.isSupportingSubscriptions()) {
            roots.add(schema.getSubscriptionType());
        }
        roots.addAll(schema.getAdditionalTypes());
        roots.addAll(schema.getDirectives());
        roots.addAll(schema.getSchemaDirectives());
        roots.add(schema.getIntrospectionSchemaType());
        Traverser<GraphQLSchemaElement> traverser = Traverser.depthFirst(GraphQLSchemaElement::getChildren);
        SchemaGraph schemaGraph = new SchemaGraph();
        class IntrospectionNode {

        }
        traverser.traverse(roots, new TraverserVisitor<GraphQLSchemaElement>() {
            @Override
            public TraversalControl enter(TraverserContext<GraphQLSchemaElement> context) {
                boolean isIntrospectionNode = false;
                if (context.thisNode() instanceof GraphQLNamedType) {
                    if (Introspection.isIntrospectionTypes((GraphQLNamedType) context.thisNode())) {
                        isIntrospectionNode = true;
                        context.setVar(IntrospectionNode.class, new IntrospectionNode());
                    }
                } else {
                    isIntrospectionNode = context.getVarFromParents(IntrospectionNode.class) != null;
                }
                if (context.thisNode() instanceof GraphQLObjectType) {
                    newObject((GraphQLObjectType) context.thisNode(), schemaGraph, isIntrospectionNode);
                }
                if (context.thisNode() instanceof GraphQLInterfaceType) {
                    newInterface((GraphQLInterfaceType) context.thisNode(), schemaGraph, isIntrospectionNode);
                }
                if (context.thisNode() instanceof GraphQLUnionType) {
                    newUnion((GraphQLUnionType) context.thisNode(), schemaGraph, isIntrospectionNode);
                }
                if (context.thisNode() instanceof GraphQLScalarType) {
                    newScalar((GraphQLScalarType) context.thisNode(), schemaGraph, isIntrospectionNode);
                }
                if (context.thisNode() instanceof GraphQLInputObjectType) {
                    newInputObject((GraphQLInputObjectType) context.thisNode(), schemaGraph, isIntrospectionNode);
                }
                if (context.thisNode() instanceof GraphQLEnumType) {
                    newEnum((GraphQLEnumType) context.thisNode(), schemaGraph, isIntrospectionNode);
                }
                if (context.thisNode() instanceof GraphQLDirective) {
                    // only continue if not applied directive
                    if (context.getParentNode() == null) {
                        newDirective((GraphQLDirective) context.thisNode(), schemaGraph);
                    }
                }
                return TraversalControl.CONTINUE;
            }

            @Override
            public TraversalControl leave(TraverserContext<GraphQLSchemaElement> context) {
                return TraversalControl.CONTINUE;
            }
        });

        ArrayList<Vertex> copyOfVertices = new ArrayList<>(schemaGraph.getVertices());
        for (Vertex vertex : copyOfVertices) {
            if (OBJECT.equals(vertex.getType())) {
                handleObjectVertex(vertex, schemaGraph, schema);
            }
            if (INTERFACE.equals(vertex.getType())) {
                handleInterfaceVertex(vertex, schemaGraph, schema);
            }
            if (UNION.equals(vertex.getType())) {
                handleUnion(vertex, schemaGraph, schema);
            }
            if (INPUT_OBJECT.equals(vertex.getType())) {
                handleInputObject(vertex, schemaGraph, schema);
            }
            if (APPLIED_DIRECTIVE.equals(vertex.getType())) {
                handleAppliedDirective(vertex, schemaGraph, schema);
            }
        }
        return schemaGraph;
    }

    private void handleAppliedDirective(Vertex appliedDirectiveVertex, SchemaGraph schemaGraph, GraphQLSchema graphQLSchema) {
//        Vertex directiveVertex = schemaGraph.getDirective(appliedDirectiveVertex.get("name"));
//        schemaGraph.addEdge(new Edge(appliedDirectiveVertex, directiveVertex));
    }

    private void handleInputObject(Vertex inputObject, SchemaGraph schemaGraph, GraphQLSchema graphQLSchema) {
        GraphQLInputObjectType inputObjectType = (GraphQLInputObjectType) graphQLSchema.getType(inputObject.get("name"));
        List<GraphQLInputObjectField> inputFields = inputObjectType.getFields();
        for (GraphQLInputObjectField inputField : inputFields) {
            Vertex inputFieldVertex = schemaGraph.findTargetVertex(inputObject, vertex -> vertex.getType().equals("InputField") &&
                    vertex.get("name").equals(inputField.getName())).get();
            handleInputField(inputFieldVertex, inputField, schemaGraph, graphQLSchema);
        }
    }

    private void handleInputField(Vertex inputFieldVertex, GraphQLInputObjectField inputField, SchemaGraph
            schemaGraph, GraphQLSchema graphQLSchema) {
        GraphQLInputType type = inputField.getType();
        GraphQLUnmodifiedType graphQLUnmodifiedType = GraphQLTypeUtil.unwrapAll(type);
        Vertex dummyTypeVertex = new Vertex(DUMMY_TYPE_VERTEX, debugPrefix + String.valueOf(counter++));
        dummyTypeVertex.setBuiltInType(inputFieldVertex.isBuiltInType());
        schemaGraph.addVertex(dummyTypeVertex);
        schemaGraph.addEdge(new Edge(inputFieldVertex, dummyTypeVertex));
        Vertex typeVertex = assertNotNull(schemaGraph.getType(graphQLUnmodifiedType.getName()));
        Edge typeEdge = new Edge(dummyTypeVertex, typeVertex);
        typeEdge.setLabel(GraphQLTypeUtil.simplePrint(type));
        schemaGraph.addEdge(typeEdge);
    }

    private void handleUnion(Vertex unionVertex, SchemaGraph schemaGraph, GraphQLSchema graphQLSchema) {
        GraphQLUnionType unionType = (GraphQLUnionType) graphQLSchema.getType(unionVertex.get("name"));
        List<GraphQLNamedOutputType> types = unionType.getTypes();
        for (GraphQLNamedOutputType unionMemberType : types) {
            Vertex unionMemberVertex = assertNotNull(schemaGraph.getType(unionMemberType.getName()));
            schemaGraph.addEdge(new Edge(unionVertex, unionMemberVertex));
        }
    }

    private void handleInterfaceVertex(Vertex interfaceVertex, SchemaGraph schemaGraph, GraphQLSchema graphQLSchema) {
        GraphQLInterfaceType interfaceType = (GraphQLInterfaceType) graphQLSchema.getType(interfaceVertex.get("name"));

        for (GraphQLNamedOutputType implementsInterface : interfaceType.getInterfaces()) {
            Vertex implementsInterfaceVertex = assertNotNull(schemaGraph.getType(implementsInterface.getName()));
            schemaGraph.addEdge(new Edge(interfaceVertex, implementsInterfaceVertex, "implements " + implementsInterface.getName()));
        }

        List<GraphQLFieldDefinition> fieldDefinitions = interfaceType.getFieldDefinitions();
        for (GraphQLFieldDefinition fieldDefinition : fieldDefinitions) {
            Vertex fieldVertex = schemaGraph.findTargetVertex(interfaceVertex, vertex -> vertex.getType().equals("Field") &&
                    vertex.get("name").equals(fieldDefinition.getName())).get();
            handleField(fieldVertex, fieldDefinition, schemaGraph, graphQLSchema);
        }

    }

    private void handleObjectVertex(Vertex objectVertex, SchemaGraph schemaGraph, GraphQLSchema graphQLSchema) {
        GraphQLObjectType objectType = graphQLSchema.getObjectType(objectVertex.get("name"));

        for (GraphQLNamedOutputType implementsInterface : objectType.getInterfaces()) {
            Vertex implementsInterfaceVertex = assertNotNull(schemaGraph.getType(implementsInterface.getName()));
            schemaGraph.addEdge(new Edge(objectVertex, implementsInterfaceVertex, "implements " + implementsInterface.getName()));
        }

        List<GraphQLFieldDefinition> fieldDefinitions = objectType.getFieldDefinitions();
        for (GraphQLFieldDefinition fieldDefinition : fieldDefinitions) {
            Vertex fieldVertex = schemaGraph.findTargetVertex(objectVertex, vertex -> vertex.getType().equals("Field") &&
                    vertex.get("name").equals(fieldDefinition.getName())).get();
            handleField(fieldVertex, fieldDefinition, schemaGraph, graphQLSchema);
        }
    }

    private void handleField(Vertex fieldVertex, GraphQLFieldDefinition fieldDefinition, SchemaGraph
            schemaGraph, GraphQLSchema graphQLSchema) {
        GraphQLOutputType type = fieldDefinition.getType();
        GraphQLUnmodifiedType graphQLUnmodifiedType = GraphQLTypeUtil.unwrapAll(type);
        Vertex dummyTypeVertex = new Vertex(DUMMY_TYPE_VERTEX, debugPrefix + String.valueOf(counter++));
        dummyTypeVertex.setBuiltInType(fieldVertex.isBuiltInType());
        schemaGraph.addVertex(dummyTypeVertex);
        schemaGraph.addEdge(new Edge(fieldVertex, dummyTypeVertex));
        Vertex typeVertex = assertNotNull(schemaGraph.getType(graphQLUnmodifiedType.getName()));
        Edge typeEdge = new Edge(dummyTypeVertex, typeVertex);
        typeEdge.setLabel(GraphQLTypeUtil.simplePrint(type));
        schemaGraph.addEdge(typeEdge);

        for (GraphQLArgument graphQLArgument : fieldDefinition.getArguments()) {
            Vertex argumentVertex = schemaGraph.findTargetVertex(fieldVertex, vertex -> vertex.getType().equals("Argument") &&
                    vertex.get("name").equals(graphQLArgument.getName())).get();
            handleArgument(argumentVertex, graphQLArgument, schemaGraph);
        }
    }

    private void handleArgument(Vertex argumentVertex, GraphQLArgument graphQLArgument, SchemaGraph schemaGraph) {
        GraphQLInputType type = graphQLArgument.getType();
        GraphQLUnmodifiedType graphQLUnmodifiedType = GraphQLTypeUtil.unwrapAll(type);
        Vertex typeVertex = assertNotNull(schemaGraph.getType(graphQLUnmodifiedType.getName()));
        Edge typeEdge = new Edge(argumentVertex, typeVertex);
        typeEdge.setLabel(GraphQLTypeUtil.simplePrint(type));
        schemaGraph.addEdge(typeEdge);
    }

    private void newObject(GraphQLObjectType graphQLObjectType, SchemaGraph schemaGraph, boolean isIntrospectionNode) {
        Vertex objectVertex = new Vertex(OBJECT, debugPrefix + String.valueOf(counter++));
        objectVertex.setBuiltInType(isIntrospectionNode);
        objectVertex.add("name", graphQLObjectType.getName());
        objectVertex.add("description", desc(graphQLObjectType.getDescription()));
        for (GraphQLFieldDefinition fieldDefinition : graphQLObjectType.getFieldDefinitions()) {
            Vertex newFieldVertex = newField(fieldDefinition, schemaGraph, isIntrospectionNode);
            schemaGraph.addVertex(newFieldVertex);
            schemaGraph.addEdge(new Edge(objectVertex, newFieldVertex));
        }
        schemaGraph.addVertex(objectVertex);
        schemaGraph.addType(graphQLObjectType.getName(), objectVertex);
        cratedAppliedDirectives(objectVertex, graphQLObjectType.getDirectives(), schemaGraph);
    }

    private Vertex newField(GraphQLFieldDefinition graphQLFieldDefinition, SchemaGraph schemaGraph, boolean isIntrospectionNode) {
        Vertex fieldVertex = new Vertex(FIELD, debugPrefix + String.valueOf(counter++));
        fieldVertex.setBuiltInType(isIntrospectionNode);
        fieldVertex.add("name", graphQLFieldDefinition.getName());
        fieldVertex.add("description", desc(graphQLFieldDefinition.getDescription()));
        for (GraphQLArgument argument : graphQLFieldDefinition.getArguments()) {
            Vertex argumentVertex = newArgument(argument, schemaGraph, isIntrospectionNode);
            schemaGraph.addVertex(argumentVertex);
            schemaGraph.addEdge(new Edge(fieldVertex, argumentVertex));
        }
        cratedAppliedDirectives(fieldVertex, graphQLFieldDefinition.getDirectives(), schemaGraph);
        return fieldVertex;
    }

    private Vertex newArgument(GraphQLArgument graphQLArgument, SchemaGraph schemaGraph, boolean isIntrospectionNode) {
        Vertex vertex = new Vertex(ARGUMENT, debugPrefix + String.valueOf(counter++));
        vertex.setBuiltInType(isIntrospectionNode);
        vertex.add("name", graphQLArgument.getName());
        vertex.add("description", desc(graphQLArgument.getDescription()));
        if (graphQLArgument.hasSetDefaultValue()) {
            vertex.add("defaultValue", AstPrinter.printAst(ValuesResolver.valueToLiteral(graphQLArgument.getArgumentDefaultValue(), graphQLArgument.getType())));
        }
        cratedAppliedDirectives(vertex, graphQLArgument.getDirectives(), schemaGraph);
        return vertex;
    }

    private void newScalar(GraphQLScalarType scalarType, SchemaGraph schemaGraph, boolean isIntrospectionNode) {
        Vertex scalarVertex = new Vertex(SCALAR, debugPrefix + String.valueOf(counter++));
        scalarVertex.setBuiltInType(isIntrospectionNode);
        if (ScalarInfo.isGraphqlSpecifiedScalar(scalarType.getName())) {
            scalarVertex.setBuiltInType(true);
        }
        scalarVertex.add("name", scalarType.getName());
        scalarVertex.add("description", desc(scalarType.getDescription()));
        scalarVertex.add("specifiedByUrl", scalarType.getSpecifiedByUrl());
        schemaGraph.addVertex(scalarVertex);
        schemaGraph.addType(scalarType.getName(), scalarVertex);
        cratedAppliedDirectives(scalarVertex, scalarType.getDirectives(), schemaGraph);
    }

    private void newInterface(GraphQLInterfaceType interfaceType, SchemaGraph schemaGraph, boolean isIntrospectionNode) {
        Vertex interfaceVertex = new Vertex(INTERFACE, debugPrefix + String.valueOf(counter++));
        interfaceVertex.setBuiltInType(isIntrospectionNode);
        interfaceVertex.add("name", interfaceType.getName());
        interfaceVertex.add("description", desc(interfaceType.getDescription()));
        for (GraphQLFieldDefinition fieldDefinition : interfaceType.getFieldDefinitions()) {
            Vertex newFieldVertex = newField(fieldDefinition, schemaGraph, isIntrospectionNode);
            schemaGraph.addVertex(newFieldVertex);
            schemaGraph.addEdge(new Edge(interfaceVertex, newFieldVertex));
        }
        schemaGraph.addVertex(interfaceVertex);
        schemaGraph.addType(interfaceType.getName(), interfaceVertex);
        cratedAppliedDirectives(interfaceVertex, interfaceType.getDirectives(), schemaGraph);
    }

    private void newEnum(GraphQLEnumType enumType, SchemaGraph schemaGraph, boolean isIntrospectionNode) {
        Vertex enumVertex = new Vertex(ENUM, debugPrefix + String.valueOf(counter++));
        enumVertex.setBuiltInType(isIntrospectionNode);
        enumVertex.add("name", enumType.getName());
        enumVertex.add("description", desc(enumType.getDescription()));
        for (GraphQLEnumValueDefinition enumValue : enumType.getValues()) {
            Vertex enumValueVertex = new Vertex(ENUM_VALUE, debugPrefix + String.valueOf(counter++));
            enumValueVertex.setBuiltInType(isIntrospectionNode);
            enumValueVertex.add("name", enumValue.getName());
            schemaGraph.addVertex(enumValueVertex);
            schemaGraph.addEdge(new Edge(enumVertex, enumValueVertex));
            cratedAppliedDirectives(enumValueVertex, enumValue.getDirectives(), schemaGraph);
        }
        schemaGraph.addVertex(enumVertex);
        schemaGraph.addType(enumType.getName(), enumVertex);
        cratedAppliedDirectives(enumVertex, enumType.getDirectives(), schemaGraph);
    }

    private void newUnion(GraphQLUnionType unionType, SchemaGraph schemaGraph, boolean isIntrospectionNode) {
        Vertex unionVertex = new Vertex(UNION, debugPrefix + String.valueOf(counter++));
        unionVertex.setBuiltInType(isIntrospectionNode);
        unionVertex.add("name", unionType.getName());
        unionVertex.add("description", desc(unionType.getDescription()));
        schemaGraph.addVertex(unionVertex);
        schemaGraph.addType(unionType.getName(), unionVertex);
        cratedAppliedDirectives(unionVertex, unionType.getDirectives(), schemaGraph);
    }

    private void newInputObject(GraphQLInputObjectType inputObject, SchemaGraph schemaGraph, boolean isIntrospectionNode) {
        Vertex inputObjectVertex = new Vertex(INPUT_OBJECT, debugPrefix + String.valueOf(counter++));
        inputObjectVertex.setBuiltInType(isIntrospectionNode);
        inputObjectVertex.add("name", inputObject.getName());
        inputObjectVertex.add("description", desc(inputObject.getDescription()));
        for (GraphQLInputObjectField inputObjectField : inputObject.getFieldDefinitions()) {
            Vertex newInputField = newInputField(inputObjectField, schemaGraph, isIntrospectionNode);
            Edge newEdge = new Edge(inputObjectVertex, newInputField);
            schemaGraph.addEdge(newEdge);
        }
        schemaGraph.addVertex(inputObjectVertex);
        schemaGraph.addType(inputObject.getName(), inputObjectVertex);
        cratedAppliedDirectives(inputObjectVertex, inputObject.getDirectives(), schemaGraph);
    }

    private void cratedAppliedDirectives(Vertex from, List<GraphQLDirective> appliedDirectives, SchemaGraph
            schemaGraph) {
        for (GraphQLDirective appliedDirective : appliedDirectives) {
            Vertex appliedDirectiveVertex = new Vertex(APPLIED_DIRECTIVE, debugPrefix + String.valueOf(counter++));
            appliedDirectiveVertex.add("name", appliedDirective.getName());
            for (GraphQLArgument appliedArgument : appliedDirective.getArguments()) {
                Vertex appliedArgumentVertex = new Vertex(APPLIED_ARGUMENT, debugPrefix + String.valueOf(counter++));
                appliedArgumentVertex.add("name", appliedArgument.getName());
                if (appliedArgument.hasSetValue()) {
                    appliedArgumentVertex.add("value", AstPrinter.printAst(ValuesResolver.valueToLiteral(appliedArgument.getArgumentValue(), appliedArgument.getType())));
                }
                schemaGraph.addVertex(appliedArgumentVertex);
                schemaGraph.addEdge(new Edge(appliedDirectiveVertex, appliedArgumentVertex));
            }
            schemaGraph.addVertex(appliedDirectiveVertex);
            schemaGraph.addEdge(new Edge(from, appliedDirectiveVertex));
        }
    }

    private void newDirective(GraphQLDirective directive, SchemaGraph schemaGraph) {
        Vertex directiveVertex = new Vertex(DIRECTIVE, debugPrefix + String.valueOf(counter++));
        directiveVertex.add("name", directive.getName());
        directiveVertex.add("repeatable", directive.isRepeatable());
        directiveVertex.add("locations", directive.validLocations());
        boolean graphqlSpecified = DirectiveInfo.isGraphqlSpecifiedDirective(directive.getName());
        directiveVertex.setBuiltInType(graphqlSpecified);
        directiveVertex.add("description", desc(directive.getDescription()));
        for (GraphQLArgument argument : directive.getArguments()) {
            Vertex argumentVertex = newArgument(argument, schemaGraph, graphqlSpecified);
            schemaGraph.addVertex(argumentVertex);
            schemaGraph.addEdge(new Edge(directiveVertex, argumentVertex));
        }
        schemaGraph.addDirective(directive.getName(), directiveVertex);
        schemaGraph.addVertex(directiveVertex);
    }

    private Vertex newInputField(GraphQLInputObjectField inputField, SchemaGraph schemaGraph, boolean isIntrospectionNode) {
        Vertex vertex = new Vertex(INPUT_FIELD, debugPrefix + String.valueOf(counter++));
        schemaGraph.addVertex(vertex);
        vertex.setBuiltInType(isIntrospectionNode);
        vertex.add("name", inputField.getName());
        vertex.add("description", desc(inputField.getDescription()));
        if (inputField.hasSetDefaultValue()) {
            vertex.add("defaultValue", AstPrinter.printAst(ValuesResolver.valueToLiteral(inputField.getInputFieldDefaultValue(), inputField.getType())));
        }
        cratedAppliedDirectives(vertex, inputField.getDirectives(), schemaGraph);
        return vertex;
    }

    private String desc(String desc) {
        return "";
//        return desc != null ? desc.replace("\n", "\\n") : null;
    }

}