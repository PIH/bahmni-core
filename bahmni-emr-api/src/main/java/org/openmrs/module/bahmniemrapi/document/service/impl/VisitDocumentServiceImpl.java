package org.openmrs.module.bahmniemrapi.document.service.impl;

import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.EncounterRole;
import org.openmrs.EncounterType;
import org.openmrs.Location;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.Provider;
import org.openmrs.Visit;
import org.openmrs.VisitType;
import org.openmrs.api.ConceptService;
import org.openmrs.api.EncounterService;
import org.openmrs.api.VisitService;
import org.openmrs.api.context.Context;
import org.openmrs.module.bahmniemrapi.document.contract.Document;
import org.openmrs.module.bahmniemrapi.document.contract.VisitDocumentRequest;
import org.openmrs.module.bahmniemrapi.document.service.VisitDocumentService;
import org.openmrs.module.bahmniemrapi.encountertransaction.matcher.EncounterProviderMatcher;
import org.openmrs.module.emrapi.encounter.EncounterParameters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class VisitDocumentServiceImpl implements VisitDocumentService {

    public static final String DOCUMENT_OBS_GROUP_CONCEPT_NAME = "Document";

    private VisitService visitService;
    private ConceptService conceptService;
    private EncounterService encounterService;

    @Autowired
    public VisitDocumentServiceImpl(VisitService visitService, ConceptService conceptService, EncounterService encounterService) {
        this.visitService = visitService;
        this.conceptService = conceptService;
        this.encounterService = encounterService;
    }

    @Override
    public Visit upload(VisitDocumentRequest visitDocumentRequest) {
        Patient patient = Context.getPatientService().getPatientByUuid(visitDocumentRequest.getPatientUuid());

        Visit visit = findOrCreateVisit(visitDocumentRequest, patient);

        Date encounterDate = (visit.getStopDatetime() != null) ? visit.getStartDatetime() : new Date();

        Encounter encounter = findOrCreateEncounter(visit, visitDocumentRequest.getEncounterTypeUuid(), encounterDate, patient, visitDocumentRequest.getProviderUuid(), visitDocumentRequest.getLocationUuid());
        visit.addEncounter(encounter);

        updateEncounter(encounter, encounterDate, visitDocumentRequest.getDocuments());

        return Context.getVisitService().saveVisit(visit);
    }

    private void updateEncounter(Encounter encounter, Date encounterDateTime, List<Document> documents) {
        Concept imageConcept = conceptService.getConceptByName(DOCUMENT_OBS_GROUP_CONCEPT_NAME);
        LinkedHashSet<Obs> observations = new LinkedHashSet<>(encounter.getAllObs());

        for (Document document : documents) {
            Concept testConcept = conceptService.getConceptByUuid(document.getTestUuid());
            Obs parentObservation = findOrCreateParentObs(encounter, encounterDateTime, testConcept, document.getObsUuid());
            String url = document.getImage();

            if (document.isNew()) {
                parentObservation.addGroupMember(newObs(parentObservation.getObsDatetime(), imageConcept, url, null, encounter));
            }
            if (document.shouldVoidDocument()) {
                voidDocumentObservationTree(parentObservation);
            } else if (document.hasConceptChanged(parentObservation.getConcept().getUuid())) {
                voidDocumentObservationTree(parentObservation);
                parentObservation = newObs(parentObservation.getObsDatetime(), testConcept, null, parentObservation.getLocation(), encounter);
                parentObservation.addGroupMember(newObs(parentObservation.getObsDatetime(), imageConcept, url, null, encounter));
            }
            observations.add(parentObservation);
        }

        encounter.setObs(observations);
    }

    private Obs findOrCreateParentObs(Encounter encounter, Date observationDateTime, Concept testConcept, String obsUuid) {
        Obs observation = findObservation(encounter.getAllObs(), obsUuid);
        return observation != null ? observation : newObs(observationDateTime, testConcept, null, null, encounter);
    }

    private void voidDocumentObservationTree(Obs obs) {
        obs.setVoided(true);
        Set<Obs> groupMembers = obs.getGroupMembers();
        if (groupMembers != null) {
            for (Obs groupMember : groupMembers) {
                groupMember.setVoided(true);
            }
        }
    }

    private Obs findObservation(Set<Obs> allObs, String obsUuid) {
        for (Obs obs : allObs) {
            if (obs.getUuid().equals(obsUuid)) {
                return obs;
            }
        }
        return null;
    }

    private Obs newObs(Date obsDate, Concept concept, String value, Location location, Encounter encounter) {
        Obs observation = new Obs();
        observation.setConcept(concept);
        observation.setObsDatetime(obsDate);
        observation.setPerson(encounter.getPatient());
        observation.setEncounter(encounter);
        if (value != null) {
            observation.setValueText(value);
        }
        observation.setLocation(location);
        return observation;
    }

    private Encounter findOrCreateEncounter(Visit visit, String encounterTypeUUID, Date encounterDateTime, Patient patient, String providerUuid, String locationUuid) {
        EncounterType encounterType = encounterService.getEncounterTypeByUuid(encounterTypeUUID);
        Location location = Context.getLocationService().getLocationByUuid(locationUuid);
        Provider provider = Context.getProviderService().getProviderByUuid(providerUuid);

        EncounterParameters encounterParameters = EncounterParameters.instance();
        encounterParameters.setEncounterType(encounterType).setProviders(new HashSet<>(Collections.singletonList(provider))).setLocation(location);

        Encounter existingEncounter = new EncounterProviderMatcher().findEncounter(visit, encounterParameters);
        if (existingEncounter != null) {
            return existingEncounter;
        }

        Encounter encounter = new Encounter();
        encounter.setPatient(patient);
        encounter.setEncounterType(encounterType);
        encounter.setEncounterDatetime(encounterDateTime);
        encounter.setLocation(location);
        EncounterRole encounterRoleByUuid = Context.getEncounterService().getEncounterRoleByUuid(EncounterRole.UNKNOWN_ENCOUNTER_ROLE_UUID);
        encounter.addProvider(encounterRoleByUuid, provider);
        return encounter;
    }

    private Visit createVisit(String visitTypeUUID, Date visitStartDate, Date visitEndDate, Patient patient) {
        VisitType visitType = Context.getVisitService().getVisitTypeByUuid(visitTypeUUID);
        Visit visit = new Visit();
        visit.setPatient(patient);
        visit.setVisitType(visitType);
        visit.setStartDatetime(visitStartDate);
        visit.setStopDatetime(visitEndDate);
        visit.setEncounters(new HashSet<Encounter>());
        return visit;
    }

    private Visit findOrCreateVisit(VisitDocumentRequest request, Patient patient) {
        if (request.getVisitUuid() != null) {
            return visitService.getVisitByUuid(request.getVisitUuid());
        }
        return createVisit(request.getVisitTypeUuid(), request.getVisitStartDate(), request.getVisitEndDate(), patient);
    }
}