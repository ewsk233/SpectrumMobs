package org.ewsk.spectrummobs.script

sealed interface TypeCheckResult {
    data class Success(
        val script: TypedScriptFile,
        val diagnostics: List<ScriptDiagnostic> = emptyList()
    ) : TypeCheckResult

    data class Failure(
        val diagnostics: List<ScriptDiagnostic>
    ) : TypeCheckResult
}

class TypeChecker(
    private val sourceName: String = "<memory>",
    private val apiRegistry: ScriptApiRegistry = ScriptApiRegistry.default(),
    private val ruleFunctions: Map<String, ScriptFunctionSignature> = emptyMap(),
    private val ruleAliases: Map<String, String> = emptyMap()
) {
    private val diagnostics = mutableListOf<ScriptDiagnostic>()
    private val scopes = mutableListOf<MutableMap<String, ScriptType>>()
    private var checkingHeader = false
    private var checkingRule = false
    private var ruleReturnType: ScriptType? = null
    private var ruleReturnSeen = false

    fun check(script: ScriptFile): TypeCheckResult {
        val rules = script.declarations.filterIsInstance<RuleFunctionDeclaration>().mapNotNull { checkRule(it, script.imports) }
        val skills = script.declarations.filterIsInstance<SkillDeclaration>().map { checkSkill(it, script.imports) }
        return if (diagnostics.isEmpty()) {
            TypeCheckResult.Success(TypedScriptFile(skills, rules, script.location))
        } else {
            TypeCheckResult.Failure(diagnostics)
        }
    }

    private fun checkSkill(skill: SkillDeclaration, imports: List<ImportDeclaration>): TypedSkill {
        ruleReturnType = null
        ruleReturnSeen = false
        pushScope()
        defineBuiltins()
        defineImports(imports)

        val parameters = checkParameters(skill.parameters)
        parameters.forEach { define(it.name, it.type) }

        checkingHeader = true
        val cooldown = skill.cooldown?.let { requireType(checkExpression(it), ScriptType.Duration, "cooldown must be Duration") }
        val requires = skill.requires?.let { requireType(checkExpression(it), ScriptType.Boolean, "requires must be Boolean") }
        checkingHeader = false
        val body = checkBlock(skill.body).statements

        popScope()
        return TypedSkill(skill.name, parameters, cooldown, requires, body, skill.location, source = sourceName, casting = checkCasting(skill))
    }

    private fun checkRule(function: RuleFunctionDeclaration, imports: List<ImportDeclaration>): TypedRuleFunction? {
        pushScope()
        defineBuiltins()
        defineImports(imports)
        val parameters = checkParameters(function.parameters)
        parameters.forEach { define(it.name, it.type) }
        ruleReturnType = resolveType(function.returnType, function.location)
        ruleReturnSeen = false
        checkingRule = true
        checkingHeader = true
        val body = checkBlock(function.body).statements
        checkingHeader = false
        checkingRule = false
        val returnType = ruleReturnType ?: ScriptType.Unknown
        if (!ruleReturnSeen || !alwaysReturns(function.body)) error(function.location, "All rule function paths must return ${returnType.displayName}")
        popScope()
        ruleReturnType = null
        ruleReturnSeen = false
        return if (returnType != ScriptType.Unknown) {
            TypedRuleFunction(function.name, parameters, returnType, body, sourceName, function.location)
        } else null
    }

    private fun checkParameters(parameters: List<ScriptParameterDeclaration>): List<TypedSkillParameter> {
        val result = mutableListOf<TypedSkillParameter>()
        var optionalSeen = false
        parameters.forEach { parameter ->
            if (result.any { it.name == parameter.name }) error(parameter.location, "Duplicate parameter '${parameter.name}'")
            if (parameter.name in RESERVED_PARAMETER_NAMES) error(parameter.location, "Parameter name conflicts with a built-in or skill control name: ${parameter.name}")
            val type = resolveType(parameter.typeName, parameter.location)
            val default = parameter.defaultValue?.let {
                optionalSeen = true
                val checked = checkExpression(it)
                if (!isConstantExpression(it)) error(it.location, "Parameter default must be a constant expression")
                requireType(checked, type, "Default value for '${parameter.name}'")
                checked
            }
            if (default == null && optionalSeen) error(parameter.location, "Required parameter cannot follow an optional parameter")
            result += TypedSkillParameter(parameter.name, type, default)
        }
        return result
    }

    private fun resolveType(type: TypeName, location: SourceLocation): ScriptType = when (type) {
        is TypeName.Simple -> when (type.name) {
            "Number" -> ScriptType.Number
            "String", "Text" -> ScriptType.Text
            "Boolean" -> ScriptType.Boolean
            "Duration" -> ScriptType.Duration
            "Percent", "Percentage" -> ScriptType.Percentage
            "Entity" -> ScriptType.Entity
            "Player" -> ScriptType.Player
            "Position" -> ScriptType.Position
            "Particle" -> ScriptType.Particle
            "OperationResult" -> ScriptType.OperationResult
            "Unit" -> ScriptType.Unit
            else -> {
                error(location, "Unknown type '${type.name}'")
                ScriptType.Unknown
            }
        }
        is TypeName.ListOf -> ScriptType.ListType(resolveType(type.element, location))
    }

    private fun isConstantExpression(expression: Expression): Boolean = when (expression) {
        is LiteralExpression -> true
        is UnaryExpression -> isConstantExpression(expression.operand)
        is BinaryExpression -> isConstantExpression(expression.left) && isConstantExpression(expression.right)
        else -> false
    }

    private fun alwaysReturns(block: BlockStatement): Boolean = block.statements.lastOrNull()?.let { statement ->
        when (statement) {
            is ReturnStatement -> true
            is BlockStatement -> alwaysReturns(statement)
            is IfStatement -> alwaysReturns(statement.thenBranch) && statement.elseBranch != null && alwaysReturns(statement.elseBranch)
            else -> false
        }
    } == true

    private fun defineImports(imports: List<ImportDeclaration>) {
        imports.forEach { import ->
            if (import.alias in setOf("caster", "target", "event", "phase", "interval")) {
                error(import.location, "Import alias conflicts with a built-in name: ${import.alias}")
            }
            if (ruleAliases[import.alias] != null && ruleAliases[import.alias] != import.moduleId) {
                error(import.location, "Import alias is declared more than once: ${import.alias}")
            }
            if (!ruleFunctions.keys.any { it.startsWith("${import.alias}.") }) {
                error(import.location, "Imported rule module is not available: ${import.moduleId}")
            }
            define(import.alias, ScriptType.Service(import.alias))
        }
    }

    private fun qualifiedName(expression: Expression): String? = when (expression) {
        is IdentifierExpression -> expression.name
        is MemberAccessExpression -> qualifiedName(expression.receiver)?.let { "$it.${expression.name}" }
        else -> null
    }

    private fun checkCasting(skill: SkillDeclaration): SkillCastingPolicy {
        fun text(name: String, default: String): String {
            val expression = skill.casting[name] ?: return default
            val value = (expression as? LiteralExpression)?.value as? LiteralValue.StringValue
            if (value == null || value.value.contains("${'$'}{")) {
                error(expression.location, "$name must be a constant String literal")
                return default
            }
            return value.value
        }
        fun boolean(name: String, default: Boolean): Boolean {
            val expression = skill.casting[name] ?: return default
            val value = (expression as? LiteralExpression)?.value as? LiteralValue.BooleanValue
            if (value == null) error(expression.location, "$name must be a Boolean literal")
            return value?.value ?: default
        }
        val group = text("group", "default")
        if (!group.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) {
            error(skill.casting["group"]?.location ?: skill.location, "Casting group must contain 1 to 64 letters, digits, dots, underscores or hyphens")
        }
        val concurrencyText = text("concurrency", "parallel")
        val concurrency = CastingConcurrency.entries.firstOrNull { it.name.equals(concurrencyText, ignoreCase = true) }
        if (concurrency == null) error(skill.casting["concurrency"]?.location ?: skill.location, "Concurrency must be parallel, reject or replace")
        return SkillCastingPolicy(group, concurrency ?: CastingConcurrency.PARALLEL,
            boolean("cancelOnPhaseChange", true), boolean("cleanupOnCancel", true))
    }

    private fun checkBlock(block: BlockStatement): IrBlock {
        pushScope()
        val statements = block.statements.map { checkStatement(it) }
        popScope()
        return IrBlock(statements, block.location)
    }

    private fun checkStatement(statement: Statement): IrStatement = when (statement) {
        is BlockStatement -> checkBlock(statement)
        is VariableDeclaration -> {
            val value = checkExpression(statement.initializer)
            define(statement.name, value.type)
            IrLet(statement.name, value, statement.location)
        }
        is StateAssignmentStatement -> {
            if (checkingRule) error(statement.location, "Rule functions cannot write state")
            val value = requireType(checkExpression(statement.value), ScriptType.Number, "state value must be Number")
            IrStateAssignment(statement.name, statement.operator, value, statement.location)
        }
        is WaitStatement -> {
            if (checkingRule) error(statement.location, "Rule functions cannot wait")
            val duration = requireType(checkExpression(statement.duration), ScriptType.Duration, "wait requires Duration")
            IrWait(duration, statement.location)
        }
        is IfStatement -> {
            val condition = requireType(checkExpression(statement.condition), ScriptType.Boolean, "if condition must be Boolean")
            val thenBranch = checkBlock(statement.thenBranch)
            val elseBranch = statement.elseBranch?.let(::checkBlock)
            IrIf(condition, thenBranch, elseBranch, statement.location)
        }
        is ForStatement -> {
            val iterable = checkExpression(statement.iterable)
            val elementType = (iterable.type as? ScriptType.ListType)?.elementType
            if (elementType == null) {
                error(statement.iterable.location, "for target must be a list, got ${iterable.type.displayName}")
            }
            pushScope()
            define(statement.variable, elementType ?: ScriptType.Unknown)
            val body = checkBlock(statement.body)
            popScope()
            IrFor(statement.variable, elementType ?: ScriptType.Unknown, iterable, body, statement.location)
        }
        is RepeatStatement -> {
            val count = requireType(checkExpression(statement.count), ScriptType.Number, "repeat count must be Number")
            IrRepeat(count, checkBlock(statement.body), statement.location)
        }
        is ReturnStatement -> {
            val value = statement.value?.let(::checkExpression)
            ruleReturnType?.let { expected ->
                if (value == null) error(statement.location, "Rule return requires ${expected.displayName}")
                else requireType(value, expected, "Rule return value")
            }
            if (ruleReturnType != null) ruleReturnSeen = true
            IrReturn(value, statement.location)
        }
        is ExpressionStatement -> {
            val expression = checkExpression(statement.expression)
            IrExpressionStatement(expression, statement.location)
        }
    }

    private fun checkExpression(expression: Expression): IrExpression = when (expression) {
        is LiteralExpression -> IrLiteral(expression.value, literalType(expression.value), expression.location)
        is IdentifierExpression -> {
            val type = lookup(expression.name)
            if (type == null) {
                error(expression.location, "Undefined name '${expression.name}'")
                IrErrorExpression(expression.location)
            } else {
                IrIdentifier(expression.name, type, expression.location)
            }
        }
        is MemberAccessExpression -> {
            val receiver = checkExpression(expression.receiver)
            val type = memberType(receiver.type, expression.name, expression.location)
            IrMember(receiver, expression.name, type, expression.location)
        }
        is CallExpression -> checkCall(expression)
        is BinaryExpression -> checkBinary(expression)
        is UnaryExpression -> {
            val operand = checkExpression(expression.operand)
            val type = when (expression.operator) {
                UnaryOperator.NOT -> requireType(operand, ScriptType.Boolean, "'!' requires Boolean").type
                UnaryOperator.NEGATE -> requireType(operand, ScriptType.Number, "Unary '-' requires Number").type
            }
            IrUnary(expression.operator, operand, type, expression.location)
        }
    }

    private fun checkCall(expression: CallExpression): IrExpression {
        val callee = checkExpression(expression.callee)
        val function = callee.type as? ScriptType.Function
        if (function == null) {
            error(expression.location, "Type ${callee.type.displayName} is not callable")
            return IrErrorExpression(expression.location)
        }
        if (checkingHeader && !function.safeInHeaders) {
            error(expression.location, "Side-effecting function cannot be used in cooldown or requires")
        }
        if (checkingRule) {
            val name = qualifiedName(expression.callee)
            val signature = name?.let(apiRegistry::signature)
            if (signature != null && signature.kind !in setOf(ScriptApiKind.TARGETER, ScriptApiKind.CONDITION)) {
                error(expression.location, "Rule functions can only call read-only Targeters, Conditions, or rule functions")
            }
        }

        val arguments = expression.arguments.map { argument ->
            when (argument) {
                is CallArgument.Positional -> IrCallArgument.Positional(checkExpression(argument.value))
                is CallArgument.Named -> IrCallArgument.Named(argument.name, checkExpression(argument.value))
            }
        }
        val bound = bindArguments(function.parameters, arguments, expression.location, function.allowExtraNamed)
        val typeArguments = mutableMapOf<String, ScriptType>()
        bound.forEach { argument ->
            function.parameters.firstOrNull { it.name == argument.name }?.let { parameter ->
                inferTypes(parameter.type, argument.value.type, typeArguments)
            }
        }
        bound.forEach { argument ->
            function.parameters.firstOrNull { it.name == argument.name }?.let { parameter ->
                requireType(argument.value, substitute(parameter.type, typeArguments), "Argument '${parameter.name}'")
            }
        }
        return IrCall(callee, bound, substitute(function.returnType, typeArguments), expression.location)
    }

    private fun checkBinary(expression: BinaryExpression): IrExpression {
        val left = checkExpression(expression.left)
        val right = checkExpression(expression.right)
        val type = when (expression.operator) {
            BinaryOperator.OR, BinaryOperator.AND -> {
                requireType(left, ScriptType.Boolean, "Left operand of logical operation must be Boolean")
                requireType(right, ScriptType.Boolean, "Right operand of logical operation must be Boolean")
                ScriptType.Boolean
            }
            BinaryOperator.IN -> {
                val listType = right.type as? ScriptType.ListType
                if (listType == null) {
                    error(expression.right.location, "Right operand of 'in' must be List<T>")
                } else if (!isAssignable(left.type, listType.elementType)) {
                    error(expression.left.location, "Left operand type ${left.type.displayName} is incompatible with list element type ${listType.elementType.displayName}")
                }
                ScriptType.Boolean
            }
            BinaryOperator.EQUAL, BinaryOperator.NOT_EQUAL -> {
                if (left.type != ScriptType.Null && right.type != ScriptType.Null &&
                    !isAssignable(left.type, right.type) && !isAssignable(right.type, left.type)
                ) {
                    error(expression.location, "Cannot compare ${left.type.displayName} and ${right.type.displayName}")
                }
                ScriptType.Boolean
            }
            BinaryOperator.LESS, BinaryOperator.LESS_EQUAL, BinaryOperator.GREATER, BinaryOperator.GREATER_EQUAL -> {
                if (!isComparable(left.type, right.type)) {
                    error(expression.location, "Comparison requires two compatible Number or Percent values")
                }
                ScriptType.Boolean
            }
            BinaryOperator.ADD, BinaryOperator.SUBTRACT, BinaryOperator.MULTIPLY, BinaryOperator.DIVIDE -> {
                requireType(left, ScriptType.Number, "Left operand of arithmetic operation must be Number")
                requireType(right, ScriptType.Number, "Right operand of arithmetic operation must be Number")
                ScriptType.Number
            }
        }
        return IrBinary(left, expression.operator, right, type, expression.location)
    }

    private fun memberType(receiver: ScriptType, name: String, location: SourceLocation): ScriptType {
        return when (receiver) {
            is ScriptType.Service -> apiMember(receiver.path, name, location)
            ScriptType.Entity, ScriptType.Player -> when (name) {
                "health" -> ScriptType.Health
                "position" -> ScriptType.Position
                else -> unknownMember(receiver, name, location)
            }
            ScriptType.Health -> when (name) {
                "percent" -> ScriptType.Percentage
                "value" -> ScriptType.Number
                else -> unknownMember(receiver, name, location)
            }
            ScriptType.EventContext -> when (name) {
                "name" -> ScriptType.Text
                "damage" -> ScriptType.Number
                "attacker" -> ScriptType.Entity
                "cause" -> ScriptType.Text
                "hand" -> ScriptType.Text
                else -> unknownMember(receiver, name, location)
            }
            ScriptType.OperationResult -> when (name) {
                "success" -> ScriptType.Boolean
                "id", "code", "reason" -> ScriptType.Text
                "count", "requested" -> ScriptType.Number
                else -> unknownMember(receiver, name, location)
            }
            is ScriptType.ListType -> when (name) {
                "size" -> ScriptType.Number
                "isEmpty" -> ScriptType.Boolean
                else -> unknownMember(receiver, name, location)
            }
            else -> unknownMember(receiver, name, location)
        }
    }

    private fun unknownMember(receiver: ScriptType, name: String, location: SourceLocation): ScriptType {
        error(location, "Type ${receiver.displayName} has no member '$name'")
        return ScriptType.Unknown
    }

    private fun apiMember(service: String, name: String, location: SourceLocation): ScriptType {
        val qualified = "$service.$name"
        val signature = apiRegistry.signature(qualified)
        if (signature != null) {
            return ScriptType.Function(signature.parameters, signature.returnType, signature.safeInHeaders, signature.allowExtraNamed)
        }
        val ruleKey = ruleAliases[service]?.let { "$it.$name" } ?: qualified
        val ruleSignature = ruleFunctions[qualified] ?: ruleFunctions[ruleKey]
        if (ruleSignature != null) {
            return ScriptType.Function(ruleSignature.parameters, ruleSignature.returnType, true, ruleSignature.allowExtraNamed)
        }
        return if (apiRegistry.hasNamespace(qualified) || ruleFunctions.keys.any { it.startsWith("$qualified.") }) {
            ScriptType.Service(qualified)
        } else {
            unknownMember(ScriptType.Service(service), name, location)
        }
    }

    private fun bindArguments(parameters: List<FunctionParameter>, arguments: List<IrCallArgument>, location: SourceLocation, allowExtraNamed: Boolean): List<IrCallArgument.Named> {
        val supplied = mutableSetOf<String>()
        val bound = mutableListOf<IrCallArgument.Named>()
        var positionalIndex = 0
        var seenNamed = false
        arguments.forEach { argument ->
            val name = when (argument) {
                is IrCallArgument.Named -> { seenNamed = true; argument.name }
                is IrCallArgument.Positional -> {
                    if (seenNamed) error(argument.value.location, "Positional argument cannot follow named arguments")
                    parameters.getOrNull(positionalIndex++)?.name
                }
            }
            if (name == null) {
                error(location, "Function accepts at most ${parameters.size} arguments")
            } else if (parameters.none { it.name == name } && !allowExtraNamed) {
                error(location, "Unknown named argument '$name'")
            } else if (!supplied.add(name)) {
                error(location, "Argument '$name' is supplied more than once")
            } else {
                // 保留源码的求值顺序，但将位置参数绑定为权威参数名。
                bound += IrCallArgument.Named(name, argument.value)
            }
        }
        parameters.filter { it.required && it.name !in supplied }.forEach {
            error(location, "Missing argument '${it.name}'")
        }
        return bound
    }

    private fun inferTypes(expected: ScriptType, actual: ScriptType, bindings: MutableMap<String, ScriptType>) {
        when {
            expected is ScriptType.TypeParameter -> {
                if (isAssignable(actual, expected.bound)) bindings.putIfAbsent(expected.name, actual)
            }
            expected is ScriptType.ListType && actual is ScriptType.ListType -> inferTypes(expected.elementType, actual.elementType, bindings)
        }
    }

    private fun substitute(type: ScriptType, bindings: Map<String, ScriptType>): ScriptType = when (type) {
        is ScriptType.TypeParameter -> bindings[type.name] ?: type.bound
        is ScriptType.ListType -> ScriptType.ListType(substitute(type.elementType, bindings))
        else -> type
    }

    private fun requireType(expression: IrExpression, expected: ScriptType, context: String): IrExpression {
        if (!isAssignable(expression.type, expected)) {
            error(expression.location, "$context; expected ${expected.displayName}, got ${expression.type.displayName}")
        }
        return expression
    }

    private fun literalType(value: LiteralValue): ScriptType = when (value) {
        is LiteralValue.Number -> ScriptType.Number
        is LiteralValue.StringValue -> ScriptType.Text
        is LiteralValue.Duration -> ScriptType.Duration
        is LiteralValue.Percentage -> ScriptType.Percentage
        is LiteralValue.BooleanValue -> ScriptType.Boolean
        LiteralValue.NullValue -> ScriptType.Null
    }

    private fun isComparable(left: ScriptType, right: ScriptType): Boolean =
        left == right && (left == ScriptType.Number || left == ScriptType.Percentage || left == ScriptType.Unknown)

    private fun isAssignable(actual: ScriptType, expected: ScriptType): Boolean = when {
        actual == ScriptType.Unknown || expected == ScriptType.Unknown -> true
        expected == ScriptType.Numeric && (actual == ScriptType.Number || actual == ScriptType.Percentage) -> true
        actual == expected -> true
        actual == ScriptType.Player && expected == ScriptType.Entity -> true
        actual is ScriptType.ListType && expected is ScriptType.ListType -> isAssignable(actual.elementType, expected.elementType)
        else -> false
    }

    private fun pushScope() {
        scopes += linkedMapOf()
    }

    private fun popScope() {
        scopes.removeAt(scopes.lastIndex)
    }

    private fun define(name: String, type: ScriptType) {
        scopes.last()[name] = type
    }

    private fun lookup(name: String): ScriptType? = scopes.asReversed().firstNotNullOfOrNull { it[name] }

    private fun defineBuiltins() {
        define("caster", ScriptType.Entity)
        define("target", ScriptType.Entity)
        define("event", ScriptType.EventContext)
        define("phase", ScriptType.Text)
        define("interval", ScriptType.Text)
        apiRegistry.rootNamespaces.forEach { define(it, ScriptType.Service(it)) }
        PARTICLE_NAMES.forEach { define(it, ScriptType.Particle) }
    }

    private fun error(location: SourceLocation, message: String) {
        diagnostics += ScriptDiagnostic(sourceName, location, message)
    }

    private companion object {
        val PARTICLE_NAMES = setOf("FLAME", "SOUL", "PORTAL", "END_ROD", "ELECTRIC_SPARK", "SOUL_FIRE_FLAME", "REDSTONE", "ENCHANT", "WITCH")
        val RESERVED_PARAMETER_NAMES = setOf(
            "caster", "target", "event", "phase", "interval", "id", "linked", "random", "state",
            "variables", "skills", "command", "targeter", "condition", "mechanic"
        ) + PARTICLE_NAMES
    }
}
