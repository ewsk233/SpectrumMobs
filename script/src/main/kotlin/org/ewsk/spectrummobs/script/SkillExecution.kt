package org.ewsk.spectrummobs.script

import java.util.UUID

enum class ExecutionState {
    RUNNABLE,
    WAITING,
    COMPLETED,
    CANCELLED,
    FAILED
}

sealed interface ResumeResult {
    val steps: Int

    data class NotReady(override val steps: Int = 0) : ResumeResult
    data class Suspended(val resumeAtTick: Long, override val steps: Int) : ResumeResult
    data class BudgetYielded(override val steps: Int) : ResumeResult
    data class Completed(override val steps: Int) : ResumeResult
    data class Cancelled(val reason: String, override val steps: Int) : ResumeResult
    data class Failed(val message: String, override val steps: Int) : ResumeResult
}

class SkillExecution internal constructor(
    val id: UUID,
    val skill: TypedSkill,
    val context: RuntimeContext,
    private val limits: RuntimeLimits
) {
    private sealed interface Frame

    private data class BlockFrame(
        val statements: List<IrStatement>,
        var index: Int = 0,
        val restoreOnExit: Boolean = true,
        val previousValues: MutableMap<String, RuntimeValue?> = linkedMapOf()
    ) : Frame

    private data class ForFrame(
        val variable: String,
        val values: List<RuntimeValue>,
        val body: IrBlock,
        val previousValue: RuntimeValue?,
        var index: Int = 0
    ) : Frame

    private data class RepeatFrame(
        var remaining: Long,
        val body: IrBlock
    ) : Frame

    private val frames = mutableListOf<Frame>(BlockFrame(skill.body, restoreOnExit = false))
    private val evaluator = RuntimeEvaluator()
    private var resumeAtTick: Long? = null
    private var loopIterations = 0
    private var cancellationReason: String? = null

    var state: ExecutionState = ExecutionState.RUNNABLE
        private set

    var currentLocation: SourceLocation = skill.location
        private set

    val waitingUntilTick: Long? get() = resumeAtTick

    fun resume(currentTick: Long): ResumeResult {
        if (state == ExecutionState.WAITING) {
            val target = resumeAtTick ?: currentTick
            if (currentTick < target) return ResumeResult.NotReady()
            resumeAtTick = null
            state = ExecutionState.RUNNABLE
        }
        if (state == ExecutionState.CANCELLED) {
            return ResumeResult.Cancelled(cancellationReason ?: "Execution cancelled", 0)
        }
        if (state == ExecutionState.COMPLETED) return ResumeResult.Completed(0)
        if (state == ExecutionState.FAILED) return ResumeResult.Failed("Execution has already failed", 0)

        context.currentTick = currentTick
        var steps = 0
        return try {
            while (steps < limits.maxStepsPerResume) {
                finishCompletedFrames()
                val frame = frames.lastOrNull() ?: run {
                    state = ExecutionState.COMPLETED
                    return ResumeResult.Completed(steps)
                }

                when (frame) {
                    is BlockFrame -> {
                        if (frame.index >= frame.statements.size) {
                            popFrame()
                            continue
                        }
                        val statement = frame.statements[frame.index++]
                        steps++
                        execute(statement, currentTick, frame)
                        if (state == ExecutionState.CANCELLED) {
                            return ResumeResult.Cancelled(cancellationReason ?: "Execution cancelled", steps)
                        }
                        if (state == ExecutionState.WAITING) {
                            return ResumeResult.Suspended(resumeAtTick ?: currentTick, steps)
                        }
                        if (state == ExecutionState.COMPLETED) return ResumeResult.Completed(steps)
                        finishCompletedFrames()
                        if (frames.isEmpty()) {
                            state = ExecutionState.COMPLETED
                            return ResumeResult.Completed(steps)
                        }
                    }
                    is ForFrame -> {
                        if (frame.index >= frame.values.size) {
                            popFrame()
                            continue
                        }
                        if (++loopIterations > limits.maxLoopIterations) {
                            throw RuntimeEvaluationException("Maximum loop iterations per execution exceeded: ${limits.maxLoopIterations}")
                        }
                        context.variables[frame.variable] = frame.values[frame.index++]
                        frames += BlockFrame(frame.body.statements)
                        steps++
                    }
                    is RepeatFrame -> {
                        if (frame.remaining <= 0L) {
                            popFrame()
                            continue
                        }
                        if (++loopIterations > limits.maxLoopIterations) {
                            throw RuntimeEvaluationException("Maximum loop iterations per execution exceeded: ${limits.maxLoopIterations}")
                        }
                        frame.remaining--
                        frames += BlockFrame(frame.body.statements)
                        steps++
                    }
                }
            }
            ResumeResult.BudgetYielded(steps)
        } catch (error: RuntimeEvaluationException) {
            if (state == ExecutionState.CANCELLED) return ResumeResult.Cancelled(cancellationReason ?: "Execution cancelled", steps)
            state = ExecutionState.FAILED
            ResumeResult.Failed(error.message ?: "Runtime execution failed", steps)
        } catch (error: Exception) {
            if (state == ExecutionState.CANCELLED) return ResumeResult.Cancelled(cancellationReason ?: "Execution cancelled", steps)
            state = ExecutionState.FAILED
            ResumeResult.Failed(error.message ?: error.javaClass.simpleName, steps)
        }
    }

    fun cancel(reason: String = "Execution cancelled") {
        if (state == ExecutionState.COMPLETED || state == ExecutionState.CANCELLED) return
        cancellationReason = reason
        state = ExecutionState.CANCELLED
        while (frames.isNotEmpty()) popFrame()
    }

    fun variables(): Map<String, RuntimeValue> = context.variables.toMap()

    private fun finishCompletedFrames() {
        while (frames.isNotEmpty()) {
            when (val frame = frames.last()) {
                is BlockFrame -> if (frame.index >= frame.statements.size) popFrame() else return
                is ForFrame -> if (frame.index >= frame.values.size) popFrame() else return
                is RepeatFrame -> if (frame.remaining <= 0L) popFrame() else return
            }
        }
    }

    private fun popFrame() {
        when (val frame = frames.removeAt(frames.lastIndex)) {
            is BlockFrame -> if (frame.restoreOnExit) frame.previousValues.forEach { (name, value) -> restoreVariable(name, value) }
            is ForFrame -> restoreVariable(frame.variable, frame.previousValue)
            is RepeatFrame -> Unit
        }
    }

    private fun restoreVariable(name: String, value: RuntimeValue?) {
        if (value == null) context.variables.remove(name) else context.variables[name] = value
    }

    private fun execute(statement: IrStatement, currentTick: Long, frame: BlockFrame) {
        currentLocation = statement.location
        when (statement) {
            is IrBlock -> frames += BlockFrame(statement.statements)
            is IrLet -> {
                val value = evaluator.evaluate(statement.value, context)
                if (statement.name !in frame.previousValues) frame.previousValues[statement.name] = context.variables[statement.name]
                context.variables[statement.name] = value
            }
            is IrStateAssignment -> {
                val amount = (evaluator.evaluate(statement.value, context) as? RuntimeValue.NumberValue)?.value
                    ?: throw RuntimeEvaluationException("Runtime state value is not Number")
                val current = (context.stateValue(statement.name) as? RuntimeValue.NumberValue)?.value ?: 0.0
                val value = when (statement.operator) {
                    StateAssignmentOperator.SET -> amount
                    StateAssignmentOperator.ADD -> current + amount
                    StateAssignmentOperator.SUBTRACT -> current - amount
                }
                context.setStateValue(statement.name, RuntimeValue.NumberValue(value))
            }
            is IrWait -> {
                val duration = evaluator.evaluate(statement.duration, context) as? RuntimeValue.DurationValue
                    ?: throw RuntimeEvaluationException("Runtime wait value is not Duration")
                if (duration.ticks > 0) {
                    resumeAtTick = if (Long.MAX_VALUE - currentTick < duration.ticks) Long.MAX_VALUE else currentTick + duration.ticks
                    state = ExecutionState.WAITING
                }
            }
            is IrIf -> {
                val condition = evaluator.evaluate(statement.condition, context) as? RuntimeValue.BooleanValue
                    ?: throw RuntimeEvaluationException("Runtime if condition is not Boolean")
                val branch = if (condition.value) statement.thenBranch else statement.elseBranch
                if (branch != null) frames += BlockFrame(branch.statements)
            }
            is IrFor -> {
                val iterable = evaluator.evaluate(statement.iterable, context) as? RuntimeValue.ListValue
                    ?: throw RuntimeEvaluationException("Runtime for target is not a list")
                frames += ForFrame(statement.variable, iterable.values, statement.body, context.variables[statement.variable])
            }
            is IrRepeat -> {
                val count = evaluator.evaluate(statement.count, context)
                val number = (count as? RuntimeValue.NumberValue)?.value
                    ?: throw RuntimeEvaluationException("Runtime repeat count is not Number")
                if (!number.isFinite() || number < 0.0 || number % 1.0 != 0.0) {
                    throw RuntimeEvaluationException("Runtime repeat count must be a non-negative integer")
                }
                frames += RepeatFrame(number.toLong(), statement.body)
            }
            is IrReturn -> {
                statement.value?.let { evaluator.evaluate(it, context) }
                state = ExecutionState.COMPLETED
                while (frames.isNotEmpty()) popFrame()
            }
            is IrExpressionStatement -> evaluator.evaluate(statement.expression, context)
        }
    }
}
