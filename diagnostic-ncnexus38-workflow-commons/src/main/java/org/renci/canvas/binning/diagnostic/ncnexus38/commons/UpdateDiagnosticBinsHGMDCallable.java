package org.renci.canvas.binning.diagnostic.ncnexus38.commons;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.collections.CollectionUtils;
import org.renci.canvas.binning.core.BinningException;
import org.renci.canvas.binning.core.grch38.AbstractUpdateDiagnosticBinsCallable;
import org.renci.canvas.dao.CANVASDAOBeanService;
import org.renci.canvas.dao.CANVASDAOException;
import org.renci.canvas.dao.clinbin.model.BinResultsFinalDiagnostic;
import org.renci.canvas.dao.clinbin.model.DiagnosticBinningJob;
import org.renci.canvas.dao.clinbin.model.DiagnosticGene;
import org.renci.canvas.dao.clinbin.model.MaxFrequency;
import org.renci.canvas.dao.jpa.CANVASDAOManager;
import org.renci.canvas.dao.refseq.model.Variants_80_4;
import org.renci.canvas.dao.var.model.CanonicalAllele;
import org.renci.canvas.dao.var.model.LocatedVariant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateDiagnosticBinsHGMDCallable extends AbstractUpdateDiagnosticBinsCallable {

    private static final Logger logger = LoggerFactory.getLogger(UpdateDiagnosticBinsHGMDCallable.class);

    public UpdateDiagnosticBinsHGMDCallable(CANVASDAOBeanService daoBean, DiagnosticBinningJob diagnosticBinningJob) {
        super(daoBean, diagnosticBinningJob);
    }

    @Override
    public Void call() throws BinningException {
        logger.debug("ENTERING call()");

        try {

            logger.info("Deleting BinResultsFinalDiagnostic instances by assembly id");
            logger.info(diagnosticBinningJob.getAssembly().toString());

            ExecutorService prepES = Executors.newFixedThreadPool(2);
            for (Integer diseaseClassId : Arrays.asList(1, 2, 3, 4, 5, 6)) {
                prepES.submit(() -> {
                    try {
                        daoBean.getBinResultsFinalDiagnosticDAO().deleteByDiagnosticBinningJobAndHGMDDiseaseClassId(diagnosticBinningJob,
                                diseaseClassId);
                    } catch (CANVASDAOException e) {
                        logger.error(e.getMessage(), e);
                    }
                });
            }
            prepES.shutdown();
            if (!prepES.awaitTermination(15L, TimeUnit.MINUTES)) {
                prepES.shutdownNow();
            }

            List<LocatedVariant> locatedVariantList = daoBean.getLocatedVariantDAO()
                    .findByAssemblyId(diagnosticBinningJob.getAssembly().getId());

            List<BinResultsFinalDiagnostic> binResultsFinalDiagnosticResults = new ArrayList<>();

            if (CollectionUtils.isNotEmpty(locatedVariantList)) {
                logger.info(String.format("locatedVariantList.size(): %d", locatedVariantList.size()));

                ExecutorService es = Executors.newFixedThreadPool(4);
                for (LocatedVariant locatedVariant : locatedVariantList) {

                    es.submit(() -> {

                        try {
                            List<Variants_80_4> variants = daoBean.getVariants_80_4_DAO().findByLocatedVariantId(locatedVariant.getId());

                            if (CollectionUtils.isNotEmpty(variants)) {

                                logger.debug("variants.size(): {}", variants.size());

                                for (Variants_80_4 variant : variants) {

                                    List<CanonicalAllele> foundCanonicalAlleles = daoBean.getCanonicalAlleleDAO()
                                            .findByLocatedVariantId(variant.getLocatedVariant().getId());

                                    if (CollectionUtils.isNotEmpty(foundCanonicalAlleles)) {

                                        CanonicalAllele canonicalAllele = foundCanonicalAlleles.get(0);

                                        Optional<LocatedVariant> optionalLocatedVariant = canonicalAllele.getLocatedVariants().stream()
                                                .filter(a -> a.getGenomeRef().getId().equals(2)).findAny();

                                        if (optionalLocatedVariant.isPresent()) {

                                            // we done't have hgmd data for 38, get from 37
                                            LocatedVariant locatedVariant37 = optionalLocatedVariant.get();
                                            logger.debug(locatedVariant37.toString());

                                            List<MaxFrequency> maxFrequencyList = daoBean.getMaxFrequencyDAO()
                                                    .findByLocatedVariantId(variant.getLocatedVariant().getId());

                                            if (CollectionUtils.isEmpty(maxFrequencyList)) {
                                                // if no MaxFrequency...can't bin
                                                continue;
                                            }

                                            MaxFrequency maxFrequency = maxFrequencyList.get(0);
                                            logger.debug(maxFrequency.toString());

                                            List<DiagnosticGene> diagnosticGeneList = daoBean.getDiagnosticGeneDAO()
                                                    .findByGeneIdAndDXId(variant.getGene().getId(), diagnosticBinningJob.getDx().getId());
                                            if (CollectionUtils.isEmpty(diagnosticGeneList)) {
                                                // if doesn't match a DiagnosticGene...don't use it
                                                continue;
                                            }

                                            DiagnosticGene diagnosticGene = diagnosticGeneList.get(0);
                                            logger.debug(diagnosticGene.toString());

                                            // hgmd - known pathogenic(1)
                                            BinResultsFinalDiagnostic binResultsFinalDiagnostic = findHGMDKnownPathogenic(variant,
                                                    locatedVariant37, maxFrequency, diagnosticGene);
                                            if (binResultsFinalDiagnostic != null) {
                                                BinResultsFinalDiagnostic foundBinResultsFinalDiagnostic = daoBean
                                                        .getBinResultsFinalDiagnosticDAO().findById(binResultsFinalDiagnostic.getId());
                                                if (foundBinResultsFinalDiagnostic == null) {
                                                    binResultsFinalDiagnosticResults.add(binResultsFinalDiagnostic);
                                                } else {
                                                    // just update with just hgmd values
                                                    foundBinResultsFinalDiagnostic
                                                            .setHgmdAccessionNumber(binResultsFinalDiagnostic.getHgmdAccessionNumber());
                                                    foundBinResultsFinalDiagnostic.setHgmdTag(binResultsFinalDiagnostic.getHgmdTag());
                                                    foundBinResultsFinalDiagnostic
                                                            .setHgmdDiseaseClass(binResultsFinalDiagnostic.getHgmdDiseaseClass());
                                                    binResultsFinalDiagnosticResults.add(foundBinResultsFinalDiagnostic);
                                                }
                                                continue;
                                            }

                                            // hgmd - likely pathogenic(2)
                                            binResultsFinalDiagnostic = findHGMDLikelyPathogenic(variant, locatedVariant37, maxFrequency,
                                                    diagnosticGene);
                                            if (binResultsFinalDiagnostic != null) {
                                                BinResultsFinalDiagnostic foundBinResultsFinalDiagnostic = daoBean
                                                        .getBinResultsFinalDiagnosticDAO().findById(binResultsFinalDiagnostic.getId());
                                                if (foundBinResultsFinalDiagnostic == null) {
                                                    binResultsFinalDiagnosticResults.add(binResultsFinalDiagnostic);
                                                } else {
                                                    // just update with just hgmd values
                                                    foundBinResultsFinalDiagnostic
                                                            .setHgmdAccessionNumber(binResultsFinalDiagnostic.getHgmdAccessionNumber());
                                                    foundBinResultsFinalDiagnostic.setHgmdTag(binResultsFinalDiagnostic.getHgmdTag());
                                                    foundBinResultsFinalDiagnostic
                                                            .setHgmdDiseaseClass(binResultsFinalDiagnostic.getHgmdDiseaseClass());
                                                    binResultsFinalDiagnosticResults.add(foundBinResultsFinalDiagnostic);
                                                }
                                                continue;
                                            }

                                            // hgmd - possibly pathogenic(3)
                                            binResultsFinalDiagnostic = findHGMDPossiblyPathogenic(variant, locatedVariant37, maxFrequency,
                                                    diagnosticGene);
                                            if (binResultsFinalDiagnostic != null) {
                                                BinResultsFinalDiagnostic foundBinResultsFinalDiagnostic = daoBean
                                                        .getBinResultsFinalDiagnosticDAO().findById(binResultsFinalDiagnostic.getId());
                                                if (foundBinResultsFinalDiagnostic == null) {
                                                    binResultsFinalDiagnosticResults.add(binResultsFinalDiagnostic);
                                                } else {
                                                    // just update with just hgmd values
                                                    foundBinResultsFinalDiagnostic
                                                            .setHgmdAccessionNumber(binResultsFinalDiagnostic.getHgmdAccessionNumber());
                                                    foundBinResultsFinalDiagnostic.setHgmdTag(binResultsFinalDiagnostic.getHgmdTag());
                                                    foundBinResultsFinalDiagnostic
                                                            .setHgmdDiseaseClass(binResultsFinalDiagnostic.getHgmdDiseaseClass());
                                                    binResultsFinalDiagnosticResults.add(foundBinResultsFinalDiagnostic);
                                                }
                                                continue;
                                            }

                                            // hgmd - uncertain significance(4)
                                            binResultsFinalDiagnostic = findHGMDUncertainSignificance(variant, locatedVariant37,
                                                    maxFrequency, diagnosticGene);
                                            if (binResultsFinalDiagnostic != null) {
                                                BinResultsFinalDiagnostic foundBinResultsFinalDiagnostic = daoBean
                                                        .getBinResultsFinalDiagnosticDAO().findById(binResultsFinalDiagnostic.getId());
                                                if (foundBinResultsFinalDiagnostic == null) {
                                                    binResultsFinalDiagnosticResults.add(binResultsFinalDiagnostic);
                                                } else {
                                                    // just update with just hgmd values
                                                    foundBinResultsFinalDiagnostic
                                                            .setHgmdAccessionNumber(binResultsFinalDiagnostic.getHgmdAccessionNumber());
                                                    foundBinResultsFinalDiagnostic.setHgmdTag(binResultsFinalDiagnostic.getHgmdTag());
                                                    foundBinResultsFinalDiagnostic
                                                            .setHgmdDiseaseClass(binResultsFinalDiagnostic.getHgmdDiseaseClass());
                                                    binResultsFinalDiagnosticResults.add(foundBinResultsFinalDiagnostic);
                                                }
                                                continue;
                                            }

                                            // hgmd - likely benign(5)
                                            binResultsFinalDiagnostic = findHGMDLikelyBenign(variant, locatedVariant37, maxFrequency,
                                                    diagnosticGene);
                                            if (binResultsFinalDiagnostic != null) {
                                                BinResultsFinalDiagnostic foundBinResultsFinalDiagnostic = daoBean
                                                        .getBinResultsFinalDiagnosticDAO().findById(binResultsFinalDiagnostic.getId());
                                                if (foundBinResultsFinalDiagnostic == null) {
                                                    binResultsFinalDiagnosticResults.add(binResultsFinalDiagnostic);
                                                } else {
                                                    // just update with just hgmd values
                                                    foundBinResultsFinalDiagnostic
                                                            .setHgmdAccessionNumber(binResultsFinalDiagnostic.getHgmdAccessionNumber());
                                                    foundBinResultsFinalDiagnostic.setHgmdTag(binResultsFinalDiagnostic.getHgmdTag());
                                                    foundBinResultsFinalDiagnostic
                                                            .setHgmdDiseaseClass(binResultsFinalDiagnostic.getHgmdDiseaseClass());
                                                    binResultsFinalDiagnosticResults.add(foundBinResultsFinalDiagnostic);
                                                }
                                                continue;
                                            }

                                            // hgmd almost certainly benign(6)
                                            binResultsFinalDiagnostic = findHGMDAlmostCertainlyBenign(variant, locatedVariant37,
                                                    maxFrequency, diagnosticGene);
                                            if (binResultsFinalDiagnostic != null) {
                                                BinResultsFinalDiagnostic foundBinResultsFinalDiagnostic = daoBean
                                                        .getBinResultsFinalDiagnosticDAO().findById(binResultsFinalDiagnostic.getId());
                                                if (foundBinResultsFinalDiagnostic == null) {
                                                    binResultsFinalDiagnosticResults.add(binResultsFinalDiagnostic);
                                                } else {
                                                    // just update with just hgmd values
                                                    foundBinResultsFinalDiagnostic
                                                            .setHgmdAccessionNumber(binResultsFinalDiagnostic.getHgmdAccessionNumber());
                                                    foundBinResultsFinalDiagnostic.setHgmdTag(binResultsFinalDiagnostic.getHgmdTag());
                                                    foundBinResultsFinalDiagnostic
                                                            .setHgmdDiseaseClass(binResultsFinalDiagnostic.getHgmdDiseaseClass());
                                                    binResultsFinalDiagnosticResults.add(foundBinResultsFinalDiagnostic);
                                                }
                                                continue;
                                            }

                                        }

                                    }

                                }

                            }

                        } catch (CANVASDAOException e) {
                            logger.error(e.getMessage(), e);
                        }
                    });
                }
                es.shutdown();
                if (!es.awaitTermination(1L, TimeUnit.HOURS)) {
                    es.shutdownNow();
                }
            }

            logger.info("binResultsFinalDiagnosticResults.size(): {}", binResultsFinalDiagnosticResults.size());

            for (BinResultsFinalDiagnostic binResultsFinalDiagnostic : binResultsFinalDiagnosticResults) {
                logger.info(binResultsFinalDiagnostic.toString());
                daoBean.getBinResultsFinalDiagnosticDAO().save(binResultsFinalDiagnostic);
            }

        } catch (Exception e) {
            logger.error(e.getMessage(), e);
            throw new BinningException(e);
        }

        return null;
    }

    public static void main(String[] args) {
        try {
            CANVASDAOManager daoMgr = CANVASDAOManager.getInstance();
            DiagnosticBinningJob binningJob = daoMgr.getDAOBean().getDiagnosticBinningJobDAO().findById(4218);
            UpdateDiagnosticBinsHGMDCallable callable = new UpdateDiagnosticBinsHGMDCallable(daoMgr.getDAOBean(), binningJob);
            callable.call();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
