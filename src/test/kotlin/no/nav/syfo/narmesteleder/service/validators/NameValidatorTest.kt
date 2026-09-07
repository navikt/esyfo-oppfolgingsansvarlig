package no.nav.syfo.narmesteleder.service.validators

import faker
import io.kotest.assertions.throwables.shouldNotThrow
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import linemanager
import linemanagerRevoke
import no.nav.syfo.application.api.ErrorType
import no.nav.syfo.application.exception.ApiErrorException
import no.nav.syfo.application.metric.METRICS_NS
import no.nav.syfo.application.metric.METRICS_REGISTRY
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.pdl.Person
import no.nav.syfo.pdl.client.Navn

class NameValidatorTest :
    DescribeSpec({
        fun person(
            lastName: String,
            fnr: String,
            firstName: String = faker.name().firstName(),
            middleName: String? = null,
        ): Person = Person(
            name = Navn(
                fornavn = firstName,
                mellomnavn = middleName,
                etternavn = lastName,
            ),
            nationalIdentificationNumber = PersonalIdentificationNumber(fnr),
        )

        fun personWithParallelLastNames(lastNames: List<String>, fnr: String): Person = Person(
            name = Navn(
                fornavn = faker.name().firstName(),
                mellomnavn = null,
                etternavn = lastNames.first(),
            ),
            names = lastNames.map { lastName ->
                Navn(
                    fornavn = faker.name().firstName(),
                    mellomnavn = null,
                    etternavn = lastName,
                )
            },
            nationalIdentificationNumber = PersonalIdentificationNumber(fnr),
        )

        describe("validateLinemanagerLastName") {
            it("should throw BadRequestException if lastname of PdlPerson and manager does not match") {
                val linemanager = linemanager()
                val manager = person(
                    lastName = linemanager.manager.lastName.reversed(),
                    fnr = linemanager.manager.nationalIdentificationNumber.value,
                )

                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateLinemanagerLastName(manager, linemanager)
                }

                exception.message shouldBe "Last name for linemanager does not correspond with registered value for the given national identification number"
                exception.type shouldBe ErrorType.LINEMANAGER_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
            }

            it("should not throw BadRequestException if lastname of PdlPerson and manager matches case insensitively") {
                val linemanager = linemanager()
                val manager = person(
                    lastName = linemanager.manager.lastName.lowercase(),
                    fnr = linemanager.manager.nationalIdentificationNumber.value,
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateLinemanagerLastName(manager, linemanager)
                }
            }

            it("should not throw when linemanager last name matches one of the parallel last names from PDL") {
                val linemanager = linemanager()
                val manager = personWithParallelLastNames(
                    lastNames = listOf(
                        linemanager.manager.lastName.reversed(),
                        linemanager.manager.lastName,
                    ),
                    fnr = linemanager.manager.nationalIdentificationNumber.value,
                )
                val attemptedBefore = parallelNamesValidationCount(result = RESULT_ATTEMPTED)
                val successBefore = parallelNamesValidationCount(result = RESULT_SUCCESS)
                val failedBefore = parallelNamesValidationCount(result = RESULT_FAILED)

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateLinemanagerLastName(manager, linemanager)
                }

                parallelNamesValidationCount(result = RESULT_ATTEMPTED) shouldBeExactly attemptedBefore + 1.0
                parallelNamesValidationCount(result = RESULT_SUCCESS) shouldBeExactly successBefore + 1.0
                parallelNamesValidationCount(result = RESULT_FAILED) shouldBeExactly failedBefore
            }

            it("should throw when linemanager last name matches none of the parallel last names from PDL") {
                val linemanager = linemanager()
                val manager = personWithParallelLastNames(
                    lastNames = listOf(
                        "${linemanager.manager.lastName} INVALID_A",
                        "${linemanager.manager.lastName} INVALID_B",
                    ),
                    fnr = linemanager.manager.nationalIdentificationNumber.value,
                )
                val attemptedBefore = parallelNamesValidationCount(result = RESULT_ATTEMPTED)
                val successBefore = parallelNamesValidationCount(result = RESULT_SUCCESS)
                val failedBefore = parallelNamesValidationCount(result = RESULT_FAILED)

                shouldThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateLinemanagerLastName(manager, linemanager)
                }

                parallelNamesValidationCount(result = RESULT_ATTEMPTED) shouldBeExactly attemptedBefore + 1.0
                parallelNamesValidationCount(result = RESULT_SUCCESS) shouldBeExactly successBefore
                parallelNamesValidationCount(result = RESULT_FAILED) shouldBeExactly failedBefore + 1.0
            }
        }

        describe("validateEmployeeLastName with Linemanager") {
            it("should not throw when employee last name matches case insensitively") {
                val linemanager = linemanager()
                val employee = person(
                    lastName = linemanager.lastName.lowercase(),
                    fnr = linemanager.employeeIdentificationNumber.value,
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }
            }

            it("should not throw when employee last name matches exactly") {
                val linemanager = linemanager()
                val employee = person(
                    lastName = linemanager.lastName,
                    fnr = linemanager.employeeIdentificationNumber.value,
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }
            }

            it("should throw BadRequestException when employee last name does not match") {
                val linemanager = linemanager()
                val employee = person(
                    lastName = linemanager.lastName.reversed(),
                    fnr = linemanager.employeeIdentificationNumber.value,
                )

                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }

                exception.message shouldBe "Last name for employee on sick leave does not correspond with registered value for the given national identification number"
                exception.type shouldBe ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
            }

            it("should not throw when employee last name matches one of the parallel last names from PDL") {
                val linemanager = linemanager()
                val employee = personWithParallelLastNames(
                    lastNames = listOf(
                        linemanager.lastName.reversed(),
                        linemanager.lastName,
                    ),
                    fnr = linemanager.employeeIdentificationNumber.value,
                )
                val attemptedBefore = parallelNamesValidationCount(result = RESULT_ATTEMPTED)
                val successBefore = parallelNamesValidationCount(result = RESULT_SUCCESS)
                val failedBefore = parallelNamesValidationCount(result = RESULT_FAILED)

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }

                parallelNamesValidationCount(result = RESULT_ATTEMPTED) shouldBeExactly attemptedBefore + 1.0
                parallelNamesValidationCount(result = RESULT_SUCCESS) shouldBeExactly successBefore + 1.0
                parallelNamesValidationCount(result = RESULT_FAILED) shouldBeExactly failedBefore
            }

            it("should throw when employee last name matches none of the parallel last names from PDL") {
                val linemanager = linemanager()
                val employee = personWithParallelLastNames(
                    lastNames = listOf(
                        "${linemanager.lastName} INVALID_A",
                        "${linemanager.lastName} INVALID_B",
                    ),
                    fnr = linemanager.employeeIdentificationNumber.value,
                )
                val attemptedBefore = parallelNamesValidationCount(result = RESULT_ATTEMPTED)
                val successBefore = parallelNamesValidationCount(result = RESULT_SUCCESS)
                val failedBefore = parallelNamesValidationCount(result = RESULT_FAILED)

                shouldThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }

                parallelNamesValidationCount(result = RESULT_ATTEMPTED) shouldBeExactly attemptedBefore + 1.0
                parallelNamesValidationCount(result = RESULT_SUCCESS) shouldBeExactly successBefore
                parallelNamesValidationCount(result = RESULT_FAILED) shouldBeExactly failedBefore + 1.0
            }
        }

        describe("validateEmployeeLastName with LinemanagerRevoke") {
            it("should not throw when employee last name matches case insensitively") {
                val linemanagerRevoke = linemanagerRevoke()
                val employee = person(
                    lastName = linemanagerRevoke.lastName.lowercase(),
                    fnr = linemanagerRevoke.employeeIdentificationNumber.value,
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanagerRevoke)
                }
            }

            it("should throw BadRequestException when employee last name does not match") {
                val linemanagerRevoke = linemanagerRevoke()
                val employee = person(
                    lastName = linemanagerRevoke.lastName.reversed(),
                    fnr = linemanagerRevoke.employeeIdentificationNumber.value,
                )

                val exception = shouldThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanagerRevoke)
                }

                exception.message shouldBe "Last name for employee on sick leave does not correspond with registered value for the given national identification number"
                exception.type shouldBe ErrorType.EMPLOYEE_NAME_NATIONAL_IDENTIFICATION_NUMBER_MISMATCH
            }

            it("should not throw when employee last name matches one of the parallel last names from PDL") {
                val linemanagerRevoke = linemanagerRevoke()
                val employee = personWithParallelLastNames(
                    lastNames = listOf(
                        linemanagerRevoke.lastName.reversed(),
                        linemanagerRevoke.lastName,
                    ),
                    fnr = linemanagerRevoke.employeeIdentificationNumber.value,
                )
                val attemptedBefore = parallelNamesValidationCount(result = RESULT_ATTEMPTED)
                val successBefore = parallelNamesValidationCount(result = RESULT_SUCCESS)
                val failedBefore = parallelNamesValidationCount(result = RESULT_FAILED)

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanagerRevoke)
                }

                parallelNamesValidationCount(result = RESULT_ATTEMPTED) shouldBeExactly attemptedBefore + 1.0
                parallelNamesValidationCount(result = RESULT_SUCCESS) shouldBeExactly successBefore + 1.0
                parallelNamesValidationCount(result = RESULT_FAILED) shouldBeExactly failedBefore
            }

            it("should throw when employee last name matches none of the parallel last names from PDL") {
                val linemanagerRevoke = linemanagerRevoke()
                val employee = personWithParallelLastNames(
                    lastNames = listOf(
                        "${linemanagerRevoke.lastName} INVALID_A",
                        "${linemanagerRevoke.lastName} INVALID_B",
                    ),
                    fnr = linemanagerRevoke.employeeIdentificationNumber.value,
                )
                val attemptedBefore = parallelNamesValidationCount(result = RESULT_ATTEMPTED)
                val successBefore = parallelNamesValidationCount(result = RESULT_SUCCESS)
                val failedBefore = parallelNamesValidationCount(result = RESULT_FAILED)

                shouldThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanagerRevoke)
                }

                parallelNamesValidationCount(result = RESULT_ATTEMPTED) shouldBeExactly attemptedBefore + 1.0
                parallelNamesValidationCount(result = RESULT_SUCCESS) shouldBeExactly successBefore
                parallelNamesValidationCount(result = RESULT_FAILED) shouldBeExactly failedBefore + 1.0
            }
        }

        describe("name matching") {
            it("normalizes Unicode, whitespace, apostrophes, and hyphens before exact matching") {
                NameValidator.determineMatchType(
                    nameToValidate = "  A\u030Astr\u00F6m\u2011O\u2019Connor  ",
                    pdlLastNames = listOf("\u00C5str\u00F6m-O'Connor"),
                    nameSource = NAME_SOURCE_SINGLE,
                ) shouldBe NameMatchType.EXACT
            }

            it("does not remove unknown characters while normalizing") {
                NameValidator.determineMatchType(
                    nameToValidate = "O*Connor",
                    pdlLastNames = listOf("OConnor"),
                    nameSource = NAME_SOURCE_SINGLE,
                ) shouldBe NameMatchType.NONE
            }

            it("only uses fuzzy matching when both names have at least four Unicode letters") {
                NameValidator.determineMatchType(
                    nameToValidate = "Aas",
                    pdlLastNames = listOf("Aar"),
                    nameSource = NAME_SOURCE_SINGLE,
                ) shouldBe NameMatchType.NONE

                NameValidator.determineMatchType(
                    nameToValidate = "Hansen",
                    pdlLastNames = listOf("Hanson"),
                    nameSource = NAME_SOURCE_SINGLE,
                ) shouldBe NameMatchType.FUZZY
            }

            it("uses an inclusive fuzzy matching threshold") {
                NameValidator.passesFuzzyThreshold(0.93) shouldBe true
                NameValidator.passesFuzzyThreshold(0.9301) shouldBe true
                NameValidator.passesFuzzyThreshold(0.9299) shouldBe false
            }

            it("checks every parallel PDL name for an exact match before fuzzy matching") {
                NameValidator.determineMatchType(
                    nameToValidate = "Hansen",
                    pdlLastNames = listOf("Hanson", "Hansen"),
                    nameSource = NAME_SOURCE_PARALLEL,
                ) shouldBe NameMatchType.EXACT
            }

            it("classifies a fuzzy match in a parallel PDL name") {
                NameValidator.determineMatchType(
                    nameToValidate = "Hansen",
                    pdlLastNames = listOf("Haugland", "Hanson"),
                    nameSource = NAME_SOURCE_PARALLEL,
                ) shouldBe NameMatchType.FUZZY
            }

            it("does not classify clearly different names as fuzzy matches") {
                NameValidator.determineMatchType(
                    nameToValidate = "Hansen",
                    pdlLastNames = listOf("Haugland"),
                    nameSource = NAME_SOURCE_SINGLE,
                ) shouldBe NameMatchType.NONE
            }

            it("accepts fuzzy matches and records them as accepted") {
                val linemanager = linemanager().copy(lastName = "Hansen")
                val employee = person(
                    lastName = "Hanson",
                    fnr = linemanager.employeeIdentificationNumber.value,
                )
                val before = nameValidationCount(
                    matchType = "fuzzy",
                    nameSource = NAME_SOURCE_SINGLE,
                    validationResult = "accepted",
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }

                nameValidationCount(
                    matchType = "fuzzy",
                    nameSource = NAME_SOURCE_SINGLE,
                    validationResult = "accepted",
                ) shouldBeExactly before + 1.0
            }

            it("accepts ø instead of ö") {
                val linemanager = linemanager().copy(lastName = "Strøm")
                val employee = person(
                    lastName = "Ström",
                    fnr = linemanager.employeeIdentificationNumber.value,
                )
                val before = nameValidationCount(
                    matchType = "orthographic_variant",
                    nameSource = NAME_SOURCE_SINGLE,
                    validationResult = "accepted",
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }

                nameValidationCount(
                    matchType = "orthographic_variant",
                    nameSource = NAME_SOURCE_SINGLE,
                    validationResult = "accepted",
                ) shouldBeExactly before + 1.0
            }

            it("accepts a request lastName combining PDL mellomnavn and etternavn") {
                val linemanager = linemanager().copy(lastName = "Førsteetternavn Sisteetternavn")
                val employee = person(
                    lastName = "Sisteetternavn",
                    firstName = "Fornavn",
                    middleName = "Førsteetternavn",
                    fnr = linemanager.employeeIdentificationNumber.value,
                )
                val before = nameValidationCount(
                    matchType = "exact",
                    nameSource = NAME_SOURCE_SINGLE,
                    validationResult = "accepted",
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }

                nameValidationCount(
                    matchType = "exact",
                    nameSource = NAME_SOURCE_SINGLE,
                    validationResult = "accepted",
                ) shouldBeExactly before + 1.0
            }

            it("accepts a combined PDL mellomnavn and etternavn in parallel PDL names") {
                val linemanager = linemanager().copy(lastName = "Førsteetternavn Sisteetternavn")
                val employee = person(
                    lastName = "Urelatert",
                    fnr = linemanager.employeeIdentificationNumber.value,
                ).copy(
                    names = listOf(
                        Navn(
                            fornavn = "Fornavn",
                            mellomnavn = null,
                            etternavn = "Urelatert",
                        ),
                        Navn(
                            fornavn = "Fornavn",
                            mellomnavn = "Førsteetternavn",
                            etternavn = "Sisteetternavn",
                        ),
                    ),
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }
            }

            it("accepts ö instead of ø") {
                val linemanager = linemanager().copy(lastName = "Björn")
                val employee = person(
                    lastName = "Bjørn",
                    fnr = linemanager.employeeIdentificationNumber.value,
                )
                val before = nameValidationCount(
                    matchType = "orthographic_variant",
                    nameSource = NAME_SOURCE_SINGLE,
                    validationResult = "accepted",
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }

                nameValidationCount(
                    matchType = "orthographic_variant",
                    nameSource = NAME_SOURCE_SINGLE,
                    validationResult = "accepted",
                ) shouldBeExactly before + 1.0
            }

            it("accepts e instead of é") {
                val linemanager = linemanager().copy(lastName = "Andre")
                val employee = person(
                    lastName = "André",
                    fnr = linemanager.employeeIdentificationNumber.value,
                )
                val before = nameValidationCount(
                    matchType = "orthographic_variant",
                    nameSource = NAME_SOURCE_SINGLE,
                    validationResult = "accepted",
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }

                nameValidationCount(
                    matchType = "orthographic_variant",
                    nameSource = NAME_SOURCE_SINGLE,
                    validationResult = "accepted",
                ) shouldBeExactly before + 1.0
            }

            it("accepts å instead of aa") {
                val linemanager = linemanager().copy(lastName = "Fåberg")
                val employee = person(
                    lastName = "Faaberg",
                    fnr = linemanager.employeeIdentificationNumber.value,
                )
                val before = nameValidationCount(
                    matchType = "orthographic_variant",
                    nameSource = NAME_SOURCE_SINGLE,
                    validationResult = "accepted",
                )

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }

                nameValidationCount(
                    matchType = "orthographic_variant",
                    nameSource = NAME_SOURCE_SINGLE,
                    validationResult = "accepted",
                ) shouldBeExactly before + 1.0
            }

            it("classifies approved orthographic variants in both directions") {
                listOf(
                    "Strøm" to "Ström",
                    "Ström" to "Strøm",
                    "Sæther" to "Säther",
                    "Säther" to "Sæther",
                    "Fåberg" to "Faaberg",
                    "Faaberg" to "Fåberg",
                    "André" to "Andre",
                    "Andre" to "André",
                ).forEach { (nameToValidate, pdlLastName) ->
                    NameValidator.determineMatchType(
                        nameToValidate = nameToValidate,
                        pdlLastNames = listOf(pdlLastName),
                        nameSource = NAME_SOURCE_SINGLE,
                    ) shouldBe NameMatchType.ORTHOGRAPHIC_VARIANT
                }
            }

            it("does not broaden orthographic variants to plain letters or digraphs") {
                listOf(
                    "Osterud" to "Østerud",
                    "Ost" to "Øst",
                    "Ost" to "Öst",
                    "Aer" to "Ær",
                    "Aer" to "Är",
                    "Ar" to "Ær",
                    "Ar" to "Är",
                    "Ase" to "Åse",
                ).forEach { (nameToValidate, pdlLastName) ->
                    NameValidator.determineMatchType(
                        nameToValidate = nameToValidate,
                        pdlLastNames = listOf(pdlLastName),
                        nameSource = NAME_SOURCE_SINGLE,
                    ) shouldBe NameMatchType.NONE
                }
            }

            it("counts an accepted fuzzy parallel name as successful") {
                val linemanager = linemanager().copy(lastName = "Hansen")
                val employee = personWithParallelLastNames(
                    lastNames = listOf("Haugland", "Hanson"),
                    fnr = linemanager.employeeIdentificationNumber.value,
                )
                val attemptedBefore = parallelNamesValidationCount(result = RESULT_ATTEMPTED)
                val successBefore = parallelNamesValidationCount(result = RESULT_SUCCESS)
                val failedBefore = parallelNamesValidationCount(result = RESULT_FAILED)

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }

                parallelNamesValidationCount(result = RESULT_ATTEMPTED) shouldBeExactly attemptedBefore + 1.0
                parallelNamesValidationCount(result = RESULT_SUCCESS) shouldBeExactly successBefore + 1.0
                parallelNamesValidationCount(result = RESULT_FAILED) shouldBeExactly failedBefore
            }

            it("counts an accepted orthographic variant in a non-first parallel name as successful") {
                val linemanager = linemanager().copy(lastName = "Strøm")
                val employee = personWithParallelLastNames(
                    lastNames = listOf("Haugland", "Ström"),
                    fnr = linemanager.employeeIdentificationNumber.value,
                )
                val nameValidationBefore = nameValidationCount(
                    matchType = "orthographic_variant",
                    nameSource = NAME_SOURCE_PARALLEL,
                    validationResult = "accepted",
                )
                val attemptedBefore = parallelNamesValidationCount(result = RESULT_ATTEMPTED)
                val successBefore = parallelNamesValidationCount(result = RESULT_SUCCESS)
                val failedBefore = parallelNamesValidationCount(result = RESULT_FAILED)

                shouldNotThrow<ApiErrorException.BadRequestException> {
                    NameValidator.validateEmployeeLastName(employee, linemanager)
                }

                nameValidationCount(
                    matchType = "orthographic_variant",
                    nameSource = NAME_SOURCE_PARALLEL,
                    validationResult = "accepted",
                ) shouldBeExactly nameValidationBefore + 1.0
                parallelNamesValidationCount(result = RESULT_ATTEMPTED) shouldBeExactly attemptedBefore + 1.0
                parallelNamesValidationCount(result = RESULT_SUCCESS) shouldBeExactly successBefore + 1.0
                parallelNamesValidationCount(result = RESULT_FAILED) shouldBeExactly failedBefore
            }
        }
    })

private const val PARALLEL_NAMES_VALIDATION_TOTAL = "${METRICS_NS}_parallel_names_validation_total"
private const val RESULT_TAG = "result"
private const val RESULT_ATTEMPTED = "attempted"
private const val RESULT_SUCCESS = "success"
private const val RESULT_FAILED = "failed"
private const val NAME_VALIDATION_TOTAL = "${METRICS_NS}_name_validation_total"
private const val MATCH_TYPE_TAG = "match_type"
private const val NAME_SOURCE_TAG = "name_source"
private const val VALIDATION_RESULT_TAG = "validation_result"
private const val NAME_SOURCE_SINGLE = "single"
private const val NAME_SOURCE_PARALLEL = "parallel"

private fun parallelNamesValidationCount(result: String): Double = METRICS_REGISTRY.find(PARALLEL_NAMES_VALIDATION_TOTAL)
    .tag(RESULT_TAG, result)
    .counter()
    ?.count() ?: 0.0

private fun nameValidationCount(
    matchType: String,
    nameSource: String,
    validationResult: String,
): Double = METRICS_REGISTRY.find(NAME_VALIDATION_TOTAL)
    .tags(
        MATCH_TYPE_TAG,
        matchType,
        NAME_SOURCE_TAG,
        nameSource,
        VALIDATION_RESULT_TAG,
        validationResult,
    )
    .counter()
    ?.count() ?: 0.0
