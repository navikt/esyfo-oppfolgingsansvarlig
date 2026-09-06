package no.nav.syfo.narmesteleder.service

import kotlinx.coroutines.delay
import no.nav.syfo.aareg.AaregService
import no.nav.syfo.altinn.dialogporten.service.DialogportenService
import no.nav.syfo.dinesykmeldte.IDinesykmeldteService
import no.nav.syfo.narmesteleder.db.INarmestelederDb
import no.nav.syfo.narmesteleder.db.NarmestelederBehovEntity
import no.nav.syfo.narmesteleder.domain.BehovStatus
import no.nav.syfo.narmesteleder.domain.Employee
import no.nav.syfo.narmesteleder.domain.LineManagerRequirementStatus
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementRead
import no.nav.syfo.narmesteleder.domain.LinemanagerRequirementWrite
import no.nav.syfo.narmesteleder.domain.Name
import no.nav.syfo.narmesteleder.domain.OrganizationNumber
import no.nav.syfo.narmesteleder.domain.PersonalIdentificationNumber
import no.nav.syfo.narmesteleder.domain.RevokedBy
import no.nav.syfo.narmesteleder.exception.LinemanagerRequirementNotFoundException
import no.nav.syfo.narmesteleder.exception.MissingIDException
import no.nav.syfo.pdl.PdlService
import no.nav.syfo.sykmelding.model.Arbeidsgiver
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

class NarmestelederService(
    private val nlDb: INarmestelederDb,
    private val persistLeesahNlBehov: Boolean,
    private val aaregService: AaregService,
    private val pdlService: PdlService,
    private val dinesykmeldteService: IDinesykmeldteService,
    private val dialogportenService: DialogportenService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun getLinemanagerRequirementReadById(id: UUID): LinemanagerRequirementRead = with(findBehovEntityById(id)) {
        val name = getName()
        toEmployeeLinemanagerRead(name)
    }

    private suspend fun NarmestelederBehovEntity.getName(): Name = if (fornavn != null && etternavn != null) {
        Name(
            firstName = fornavn,
            lastName = etternavn,
            middleName = mellomnavn,
        )
    } else {
        val details = pdlService.getPersonFor(sykmeldtFnr)
        val updated = this.copy(
            fornavn = details.name.fornavn,
            mellomnavn = details.name.mellomnavn,
            etternavn = details.name.etternavn,
        )
        nlDb.updateNlBehov(updated)
        Name(
            firstName = details.name.fornavn,
            lastName = details.name.etternavn,
            middleName = details.name.mellomnavn,
        )
    }

    private suspend fun findBehovEntityById(id: UUID): NarmestelederBehovEntity = nlDb.findBehovById(id)
        ?: throw LinemanagerRequirementNotFoundException("NarmestelederBehovEntity not found for id: $id")

    suspend fun updateNlBehov(
        requirementId: UUID,
        behovStatus: BehovStatus
    ) {
        val narmestelederBehovEntity = findBehovEntityById(requirementId)
        updateNlBehov(narmestelederBehovEntity, behovStatus)
    }

    suspend fun updateNlBehov(
        behovEntity: NarmestelederBehovEntity,
        behovStatus: BehovStatus
    ) {
        val updatedBehov = behovEntity.copy(
            behovStatus = behovStatus,
        )
        nlDb.updateNlBehov(updatedBehov)
        logger.info("Updated NarmestelederBehovEntity with id: ${updatedBehov.id} with status: $behovStatus")
        dialogportenService.setToCompletedInDialogporten(updatedBehov)
    }

    suspend fun findClosableBehovs(sykmeldtFnr: String, orgnummer: String): List<NarmestelederBehovEntity> = nlDb.findBehovByParameters(
        sykmeldtFnr = sykmeldtFnr,
        orgnummer = orgnummer,
        behovStatus = listOf(
            BehovStatus.BEHOV_CREATED,
            BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION
        )
    )

    suspend fun createNewNlBehov(
        nlBehov: LinemanagerRequirementWrite,
        skipSykmeldingCheck: Boolean = false,
        behovSource: BehovSource,
        arbeidsgiver: Arbeidsgiver? = null,
    ): UUID? {
        if (!persistLeesahNlBehov) {
            logger.info("Skipping persistence of LinemanagerRequirement as configured.")
            return null
        }
        if (skipDueToExistingBehov(nlBehov)) {
            return null
        }
        if (skipDueToNoActiveSykmelding(nlBehov, skipSykmeldingCheck)) {
            return null
        }

        val (behovStatus, hovedenhetOrgnummer) = if (arbeidsgiver != null) {
            getStatusAndHovedEnhetOrgnummerFromArbeidsgiver(arbeidsgiver, behovSource)
        } else {
            getStatusAndHovedEnhetOrgnummerFromArbeidsforhold(
                fnr = nlBehov.employeeIdentificationNumber.value,
                orgnummer = nlBehov.orgNumber.value,
                behovSource = behovSource,
            )
        }

        val entity = NarmestelederBehovEntity.fromLinemanagerRequirementWrite(
            linemanagerRequirementWrite = nlBehov,
            hovedenhetOrgnummer = hovedenhetOrgnummer,
            behovStatus = behovStatus,
        )
        val insertedEntity = nlDb.insertNlBehov(entity).also {
            logger.info("Inserted NarmestelederBehovEntity with id: ${it.id}")
        }
        if (!BehovStatus.errorStatusList().contains(entity.behovStatus)) {
            dialogportenService.sendToDialogporten(insertedEntity)
        }
        return insertedEntity.id
    }

    private suspend fun skipDueToNoActiveSykmelding(
        nlBehov: LinemanagerRequirementWrite,
        skipSykmeldingCheck: Boolean
    ): Boolean {
        val isActiveSykmelding = skipSykmeldingCheck ||
            dinesykmeldteService.getIsActiveSykmelding(nlBehov.employeeIdentificationNumber.value, nlBehov.orgNumber.value)
        return if (!isActiveSykmelding) {
            COUNT_CREATE_BEHOV_SKIPPED_NO_SICKLEAVE.increment()
            logger.info(
                "Not inserting NarmestelederBehovEntity as there is no active sick leave for employee with" +
                    " narmestelederId ${nlBehov.revokedLinemanagerId}"
            )
            true
        } else {
            false
        }
    }

    private suspend fun skipDueToExistingBehov(nlBehov: LinemanagerRequirementWrite): Boolean {
        val registeredPreviousBehov = findClosableBehovs(nlBehov.employeeIdentificationNumber.value, nlBehov.orgNumber.value)
            .isNotEmpty()

        return if (registeredPreviousBehov) {
            COUNT_CREATE_BEHOV_SKIPPED_HAS_PRE_EXISTING.increment()
            logger.info(
                "Not inserting NarmestelederBehovEntity since one already for employee and org"
            )
            true
        } else {
            false
        }
    }

    private suspend fun getStatusAndHovedEnhetOrgnummerFromArbeidsforhold(
        fnr: String,
        orgnummer: String,
        behovSource: BehovSource,
    ): Pair<BehovStatus, String> {
        var behovStatus = BehovStatus.BEHOV_CREATED
        val arbeidsforhold = aaregService.findArbeidsforholdByPersonIdent(fnr)
            .find { it.orgnummer == orgnummer }
        if (arbeidsforhold == null) {
            behovStatus = BehovStatus.ARBEIDSFORHOLD_NOT_FOUND
            COUNT_CREATE_BEHOV_STORED_ARBEIDSFORHOLD_NOT_FOUND.increment()
            logger.warn(
                "No arbeidsforhold found for for orgnumber $orgnummer and " +
                    "behovSource id: ${behovSource.id} type: ${behovSource.source} "
            )
        } else if (arbeidsforhold.opplysningspliktigOrgnummer == null) {
            behovStatus = BehovStatus.HOVEDENHET_NOT_FOUND
            logger.warn(
                "No hovedenhet found in arbeidsforhold for orgnumber ${arbeidsforhold.orgnummer} and " +
                    "behovSource id: ${behovSource.id} type: ${behovSource.source} "
            )
        }
        return Pair(behovStatus, arbeidsforhold?.opplysningspliktigOrgnummer ?: "UNKNOWN")
    }

    private fun getStatusAndHovedEnhetOrgnummerFromArbeidsgiver(
        arbeidsgiver: Arbeidsgiver,
        behovSource: BehovSource
    ): Pair<BehovStatus, String> {
        if (arbeidsgiver.juridiskOrgnummer == null) {
            COUNT_CREATE_BEHOV_STORED_ERROR_NO_MAIN_ORGUNIT.increment()
            logger.warn(
                "No hovedenhet found in arbeidsgiver from sykmelding for orgnumber ${arbeidsgiver.orgnummer} and " +
                    "behovSource id: ${behovSource.id} type: ${behovSource.source} "
            )
            return Pair(BehovStatus.HOVEDENHET_NOT_FOUND, "UNKNOWN")
        } else {
            return Pair(BehovStatus.BEHOV_CREATED, arbeidsgiver.juridiskOrgnummer)
        }
    }

    suspend fun getEmployeeByRequirementId(id: UUID): Employee {
        val behovEntity = findBehovEntityById(id)
        return Employee(
            nationalIdentificationNumber = PersonalIdentificationNumber(behovEntity.sykmeldtFnr),
            orgNumber = OrganizationNumber(behovEntity.orgnummer),
            lastName = behovEntity.etternavn ?: "",
        )
    }

    suspend fun getNlBehovList(
        orgNumber: OrganizationNumber,
        createdAfter: Instant,
        pageSize: Int
    ): List<LinemanagerRequirementRead> = nlDb.findBehovByParameters(
        orgNumber = orgNumber.value,
        createdAfter = createdAfter,
        status = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION),
        limit = pageSize + 1,
    ).map { it.toEmployeeLinemanagerRead(it.getName()) }

    suspend fun countNlBehov(
        orgNumber: OrganizationNumber,
        createdAfter: Instant,
    ): Long = nlDb.countBehovByParameters(
        orgNumber = orgNumber.value,
        createdAfter = createdAfter,
        status = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION),
    )

    suspend fun updateStatusOnExpiredBehovs(validDaysAfterTom: Long) {
        var count: Int
        var totalUpdated = 0
        logger.info("Starting loop for setBehovStatusForSykmeldingWithTomBeforeAndStatus")
        do {
            logger.info("Before call to setBehovStatusForSykmeldingWithTomBeforeAndStatus")
            count = nlDb.setBehovStatusForSykmeldingWithTomBeforeAndStatus(
                tomBefore = Instant.now().minus(Duration.ofDays(validDaysAfterTom)),
                fromStatus = listOf(BehovStatus.BEHOV_CREATED, BehovStatus.DIALOGPORTEN_STATUS_SET_REQUIRES_ATTENTION),
                newStatus = BehovStatus.BEHOV_EXPIRED,
            )
            totalUpdated += count
            logger.info("Update $count in iteration with setBehovStatusForSykmeldingWithTomBeforeAndStatus")
            delay(UPDATE_EXPIRED_BEHOVS_DELAY_MS.milliseconds)
        }
        while (count > 0)

        logger.info("Updated total of $totalUpdated behovs to BEHOV_EXPIRED with tom before ${Instant.now().minus(Duration.ofDays(validDaysAfterTom))}")
    }

    companion object {
        const val UPDATE_EXPIRED_BEHOVS_DELAY_MS = 500
    }
}

fun NarmestelederBehovEntity.toEmployeeLinemanagerRead(name: Name): LinemanagerRequirementRead = LinemanagerRequirementRead(
    id = this.id ?: throw MissingIDException("NarmestelederBehovEntity entity id is null"),
    employeeIdentificationNumber = PersonalIdentificationNumber(this.sykmeldtFnr),
    orgNumber = OrganizationNumber(this.orgnummer),
    mainOrgNumber = OrganizationNumber(this.hovedenhetOrgnummer),
    managerIdentificationNumber = this.narmestelederFnr?.let(::PersonalIdentificationNumber),
    name = name,
    created = this.created,
    updated = this.updated,
    status = LineManagerRequirementStatus.from(this.behovStatus),
    revokedBy = RevokedBy.from(this.behovReason),
)
