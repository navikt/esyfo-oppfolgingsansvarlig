package no.nav.syfo.narmesteleder.service.validators

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmesteleder.domain.Linemanager
import no.nav.syfo.narmesteleder.domain.LinemanagerRevoke
import no.nav.syfo.pdl.Person
import org.apache.commons.text.similarity.JaroWinklerSimilarity
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

private const val PARALLEL_NAMES_VALIDATION_TOTAL =
    "${METRICS_NS}_parallel_names_validation_total"
private const val PARALLEL_NAMES_VALIDATION_DESCRIPTION =
    "Counts parallel names validation attempts and outcomes."
private const val RESULT_TAG = "result"
private const val RESULT_ATTEMPTED = "attempted"
private const val RESULT_SUCCESS = "success"
private const val RESULT_FAILED = "failed"
private const val NAME_VALIDATION_TOTAL = "${METRICS_NS}_name_validation_total"
private const val NAME_VALIDATION_DESCRIPTION = "Counts last name validation outcomes."
private const val FUZZY_SCORE = "${METRICS_NS}_name_validation_fuzzy_score"
private const val FUZZY_SCORE_DESCRIPTION =
    "Jaro-Winkler scores after exact and orthographic variant last name matches fail."
private const val MATCH_TYPE_TAG = "match_type"
private const val NAME_SOURCE_TAG = "name_source"
private const val VALIDATION_RESULT_TAG = "validation_result"
private const val NAME_SOURCE_SINGLE = "single"
private const val NAME_SOURCE_PARALLEL = "parallel"
private const val VALIDATION_RESULT_ACCEPTED = "accepted"
private const val VALIDATION_RESULT_REJECTED = "rejected"
private const val FUZZY_MATCH_THRESHOLD = 0.93
private const val MINIMUM_FUZZY_MATCH_LETTERS = 4

private const val EMPLOYEE_NAME_VALIDATION_FAILED_MESSAGE =
    "Last name for employee on sick leave does not correspond with registered value for the given national identification number"
private const val LINEMANAGER_NAME_VALIDATION_FAILED_MESSAGE =
    "Last name for linemanager does not correspond with registered value for the given national identification number"

object NameValidator {
    private val jaroWinklerSimilarity = JaroWinklerSimilarity()
    private val parallelNamesValidationCounters: Map<String, Counter> = listOf(
        RESULT_ATTEMPTED,
        RESULT_SUCCESS,
        RESULT_FAILED,
    ).associateWith { result ->
        Counter.builder(PARALLEL_NAMES_VALIDATION_TOTAL)
            .description(PARALLEL_NAMES_VALIDATION_DESCRIPTION)
            .tag(RESULT_TAG, result)
            .register(METRICS_REGISTRY)
    }
    private val nameValidationCounters = ConcurrentHashMap<NameValidationMetricKey, Counter>()
    private val fuzzyScoreSummaries = ConcurrentHashMap<String, DistributionSummary>()

    fun validateLinemanagerLastName(
        managerPdlPerson: Person,
        linemanager: Linemanager,
    ) {
        nlrequire(
            validateLastName(linemanager.manager.lastName, managerPdlPerson),
            type = ErrorType.LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
        ) {
            LINEMANAGER_NAME_VALIDATION_FAILED_MESSAGE
        }
    }

    fun validateEmployeeLastName(
        employeePdlPerson: Person,
        linemanager: Linemanager,
    ) {
        nlrequire(
            validateLastName(linemanager.lastName, employeePdlPerson),
            type = ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
        ) {
            EMPLOYEE_NAME_VALIDATION_FAILED_MESSAGE
        }
    }

    fun validateEmployeeLastName(
        managerPdlPerson: Person,
        linemanagerRevoke: LinemanagerRevoke,
    ) {
        nlrequire(
            validateLastName(linemanagerRevoke.lastName, managerPdlPerson),
            type = ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH,
        ) {
            EMPLOYEE_NAME_VALIDATION_FAILED_MESSAGE
        }
    }

    private fun validateLastName(nameToValidate: String, pdlPerson: Person): Boolean {
        val nameSource = if (pdlPerson.hasParallelNames) NAME_SOURCE_PARALLEL else NAME_SOURCE_SINGLE
        val matchType = determineMatchType(
            nameToValidate = nameToValidate,
            pdlLastNames = pdlPerson.names.flatMap { pdlName ->
                listOfNotNull(
                    pdlName.etternavn,
                    pdlName.mellomnavn
                        ?.takeIf { it.isNotBlank() }
                        ?.let { "$it ${pdlName.etternavn}".normalizeName() },
                )
            },
            nameSource = nameSource,
        )
        val isAccepted = matchType.isAccepted

        countNameValidation(matchType, nameSource, isAccepted)
        if (pdlPerson.hasParallelNames) {
            countParallelNamesValidation(result = RESULT_ATTEMPTED)
            countParallelNamesValidation(
                result = if (isAccepted) RESULT_SUCCESS else RESULT_FAILED,
            )
        }

        return isAccepted
    }

    internal fun determineMatchType(
        nameToValidate: String,
        pdlLastNames: List<String>,
        nameSource: String,
    ): NameMatchType {
        val normalizedName = nameToValidate.normalizeName()
        val normalizedPdlNames = pdlLastNames.map { it.normalizeName() }
        if (normalizedPdlNames.any { it == normalizedName }) {
            return NameMatchType.EXACT
        }

        val orthographicName = normalizedName.canonicalizeOrthographicVariants()
        if (normalizedPdlNames.any { it.canonicalizeOrthographicVariants() == orthographicName }) {
            return NameMatchType.ORTHOGRAPHIC_VARIANT
        }

        val fuzzyScores = normalizedPdlNames.mapNotNull { pdlName ->
            fuzzyScore(normalizedName, pdlName)
        }
        fuzzyScores.maxOrNull()?.let { score ->
            countFuzzyScore(nameSource, score)
            if (passesFuzzyThreshold(score)) {
                return NameMatchType.FUZZY
            }
        }

        return NameMatchType.NONE
    }

    internal fun passesFuzzyThreshold(score: Double): Boolean = score >= FUZZY_MATCH_THRESHOLD

    private fun String.normalizeName(): String = Normalizer.normalize(this, Normalizer.Form.NFC)
        .trim()
        .replace("\\s+".toRegex(), " ")
        .replace(APOSTROPHE_VARIANTS.toRegex(), "'")
        .replace(HYPHEN_VARIANTS.toRegex(), "-")
        .uppercase()

    private fun String.canonicalizeOrthographicVariants(): String = replace("AA", "Å")
        .replace('Ö', 'Ø')
        .replace('Ä', 'Æ')
        .replace('É', 'E')

    private fun fuzzyScore(firstName: String, secondName: String): Double? = if (
        firstName.isFuzzyEligible() &&
        secondName.isFuzzyEligible()
    ) {
        jaroWinklerSimilarity.apply(firstName, secondName)
    } else {
        null
    }

    private fun String.isFuzzyEligible(): Boolean = letterCount() >= MINIMUM_FUZZY_MATCH_LETTERS &&
        all { it.isLetter() || it.isWhitespace() || it == '\'' || it == '-' }

    private fun String.letterCount(): Int = codePoints().filter(Character::isLetter).count().toInt()

    private fun countParallelNamesValidation(result: String) {
        parallelNamesValidationCounters.getValue(result).increment()
    }

    private fun countNameValidation(
        matchType: NameMatchType,
        nameSource: String,
        isAccepted: Boolean,
    ) {
        val key = NameValidationMetricKey(
            matchType = matchType,
            nameSource = nameSource,
            validationResult = if (isAccepted) VALIDATION_RESULT_ACCEPTED else VALIDATION_RESULT_REJECTED,
        )
        nameValidationCounters.computeIfAbsent(key) {
            Counter.builder(NAME_VALIDATION_TOTAL)
                .description(NAME_VALIDATION_DESCRIPTION)
                .tag(MATCH_TYPE_TAG, key.matchType.metricValue)
                .tag(NAME_SOURCE_TAG, key.nameSource)
                .tag(VALIDATION_RESULT_TAG, key.validationResult)
                .register(METRICS_REGISTRY)
        }.increment()
    }

    private fun countFuzzyScore(nameSource: String, score: Double) {
        fuzzyScoreSummaries.computeIfAbsent(nameSource) {
            DistributionSummary.builder(FUZZY_SCORE)
                .description(FUZZY_SCORE_DESCRIPTION)
                .tag(NAME_SOURCE_TAG, nameSource)
                .register(METRICS_REGISTRY)
        }.record(score)
    }

    private data class NameValidationMetricKey(
        val matchType: NameMatchType,
        val nameSource: String,
        val validationResult: String,
    )

    private const val APOSTROPHE_VARIANTS = "[\u2018\u2019\u201B\uFF07]"
    private const val HYPHEN_VARIANTS = "[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]"
}

internal enum class NameMatchType(
    val metricValue: String,
    val isAccepted: Boolean,
) {
    EXACT("exact", true),
    ORTHOGRAPHIC_VARIANT("orthographic_variant", true),
    FUZZY("fuzzy", true),
    NONE("none", false),
}
