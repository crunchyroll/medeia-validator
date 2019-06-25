package com.worldturner.medeia.parser.gson

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.worldturner.medeia.parser.JsonParserAdapter
import com.worldturner.medeia.parser.JsonTokenData
import com.worldturner.medeia.parser.JsonTokenDataAndLocationConsumer
import com.worldturner.medeia.parser.JsonTokenLocation
import com.worldturner.medeia.parser.JsonTokenType.END_OBJECT
import com.worldturner.medeia.parser.JsonTokenType.FIELD_NAME
import com.worldturner.medeia.parser.JsonTokenType.START_OBJECT
import com.worldturner.medeia.parser.JsonTokenType.VALUE_NUMBER
import com.worldturner.medeia.parser.TOKEN_END_ARRAY
import com.worldturner.medeia.parser.TOKEN_END_OBJECT
import com.worldturner.medeia.parser.TOKEN_FALSE
import com.worldturner.medeia.parser.TOKEN_NULL
import com.worldturner.medeia.parser.TOKEN_START_ARRAY
import com.worldturner.medeia.parser.TOKEN_START_OBJECT
import com.worldturner.medeia.parser.TOKEN_TRUE
import com.worldturner.medeia.pointer.JsonPointer
import com.worldturner.medeia.pointer.JsonPointerBuilder
import java.io.Reader
import java.math.BigDecimal
import java.math.BigInteger
import java.util.ArrayDeque

class GsonJsonReaderDecorator(
    input: Reader,
    private val consumer: JsonTokenDataAndLocationConsumer,
    private val inputSourceName: String?
) : JsonReader(input), JsonParserAdapter {

    private val jsonPointerBuilder = JsonPointerBuilder()
    private val dynamicTokenLocation = DynamicJsonTokenLocation()
    private var level = 0
    private val propertyNamesStack = ArrayDeque<MutableSet<String>>()

    fun consume(token: JsonTokenData) {
        jsonPointerBuilder.consume(token, dynamicTokenLocation)
        consumer.consume(token, dynamicTokenLocation)
        when (token.type) {
            START_OBJECT -> propertyNamesStack.addFirst(HashSet())
            END_OBJECT -> propertyNamesStack.removeFirst()
            FIELD_NAME -> propertyNamesStack.peek() += token.text!!
            else -> {
            }
        }
    }

    override fun nextBoolean(): Boolean =
        super.nextBoolean().also {
            consume(if (it) TOKEN_TRUE else TOKEN_FALSE)
        }

    private fun nextNumber(): Number {
        val token = peek()
        if (token == JsonToken.NUMBER) {
            val numberAsString = super.nextString()
            return if (numberAsString.contains('.'))
                BigDecimal(numberAsString).also { consume(JsonTokenData(VALUE_NUMBER, decimal = it)) }
            else
                BigInteger(numberAsString).also { consume(JsonTokenData(VALUE_NUMBER, integer = it)) }
        }
        return nextDouble()
    }

    override fun nextInt(): Int {
        val token = peek()
        if (token == JsonToken.NUMBER) {
            val numberAsString = super.nextString()
            return if (numberAsString.contains('.'))
                BigDecimal(numberAsString).also { consume(JsonTokenData(VALUE_NUMBER, decimal = it)) }.exactIntValue()
            else
                BigInteger(numberAsString).also { consume(JsonTokenData(VALUE_NUMBER, integer = it)) }.exactIntValue()
        }
        return nextInt()
    }

    override fun nextLong(): Long {
        val token = peek()
        if (token == JsonToken.NUMBER) {
            val numberAsString = super.nextString()
            return if (numberAsString.contains('.'))
                BigDecimal(numberAsString).also { consume(JsonTokenData(VALUE_NUMBER, decimal = it)) }.longValueExact()
            else
                BigInteger(numberAsString).also { consume(JsonTokenData(VALUE_NUMBER, integer = it)) }.longExactValue()
        }
        return nextLong()
    }

    override fun nextDouble(): Double {
        val token = peek()
        if (token == JsonToken.NUMBER) {
            val numberAsString = super.nextString()
            return if (numberAsString.contains('.'))
                BigDecimal(numberAsString).also { consume(JsonTokenData(VALUE_NUMBER, decimal = it)) }.toDouble()
            else
                BigInteger(numberAsString).also { consume(JsonTokenData(VALUE_NUMBER, integer = it)) }.toDouble()
        }
        return nextDouble()
    }

    override fun nextName(): String =
        super.nextName().also {
            consume(JsonTokenData(FIELD_NAME, text = it))
        }

    override fun nextString(): String {
        val token = peek()
        return super.nextString().also {
            if (token == JsonToken.NUMBER) {
                if (it.contains('.'))
                    BigDecimal(it).let { JsonTokenData(VALUE_NUMBER, decimal = it) }
                else
                    BigInteger(it).let { JsonTokenData(VALUE_NUMBER, integer = it) }
            } else {
                JsonTokenData.createText(it)
            }.also { consume(it) }
        }
    }

    override fun nextNull() =
        super.nextNull().also {
            consume(TOKEN_NULL)
        }

    override fun beginArray() {
        super.beginArray()
        consume(TOKEN_START_ARRAY)
        level++
    }

    override fun endArray() {
        super.endArray()
        level--
        consume(TOKEN_END_ARRAY)
    }

    override fun beginObject() {
        super.beginObject()
        consume(TOKEN_START_OBJECT)
        level++
    }

    override fun endObject() {
        super.endObject()
        level--
        consume(TOKEN_END_OBJECT)
    }

    inner class DynamicJsonTokenLocation : JsonTokenLocation {
        override val pointer: JsonPointer
            get() = jsonPointerBuilder.toJsonPointer()

        override val level: Int
            get() = this@GsonJsonReaderDecorator.level
        override val propertyNames: Set<String>
            get() = propertyNamesStack.peek() ?: emptySet()

        override val inputSourceName: String?
            get() = this@GsonJsonReaderDecorator.inputSourceName

        override fun toString(): String =
            inputSourceName?.let { "at $pointer in $inputSourceName" } ?: "at $pointer"
    }

    override fun parseAll() {
        loop@ do {
            var token = peek()!!
            when (token) {
                JsonToken.NUMBER -> nextNumber()
                JsonToken.STRING -> nextString()
                JsonToken.NAME -> nextName()
                JsonToken.BOOLEAN -> nextBoolean()
                JsonToken.NULL -> nextNull()
                JsonToken.BEGIN_ARRAY -> beginArray()
                JsonToken.END_ARRAY -> endArray()
                JsonToken.BEGIN_OBJECT -> beginObject()
                JsonToken.END_OBJECT -> endObject()
                JsonToken.END_DOCUMENT -> break@loop
            }
        } while (true)
    }
}

private fun BigDecimal.exactIntValue(): Int {
    val num: Long = this.longValueExact()
    // will check decimal part
    if (num.toInt().toLong() != num) {
        throw java.lang.ArithmeticException("Overflow")
    }
    return num.toInt()
}

private fun BigInteger.exactIntValue(): Int {
    val num = toInt()
    return if (this == BigInteger(num.toString()))
        num
    else
        throw ArithmeticException("BigInteger out of int range")
}

private fun BigInteger.longExactValue(): Long {
    val num = toLong()
    return if (this == BigInteger(num.toString()))
        num
    else
        throw ArithmeticException("BigInteger out of int range")
}