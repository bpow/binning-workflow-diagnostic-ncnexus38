package org.renci.canvas.binning.diagnostic.ncnexus38.executor;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.Executors;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.ServiceReference;
import org.renci.canvas.binning.diagnostic.ncnexus38.commons.AnnotateVariantsCallable;
import org.renci.canvas.dao.CANVASDAOBeanService;
import org.renci.canvas.dao.CANVASDAOException;
import org.renci.canvas.dao.clinbin.model.DiagnosticBinningJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AnnotateVariantsDelegate implements JavaDelegate {

    private static final Logger logger = LoggerFactory.getLogger(AnnotateVariantsDelegate.class);

    public AnnotateVariantsDelegate() {
        super();
    }

    @Override
    public void execute(DelegateExecution execution) {
        logger.debug("ENTERING execute(DelegateExecution)");

        Map<String, Object> variables = execution.getVariables();

        BundleContext bundleContext = FrameworkUtil.getBundle(getClass()).getBundleContext();
        ServiceReference<CANVASDAOBeanService> daoBeanServiceReference = bundleContext.getServiceReference(CANVASDAOBeanService.class);
        CANVASDAOBeanService daoBean = bundleContext.getService(daoBeanServiceReference);

        Integer binningJobId = null;
        Object o = variables.get("binningJobId");
        if (o != null && o instanceof Integer) {
            binningJobId = (Integer) o;
        }

        try {
            DiagnosticBinningJob binningJob = daoBean.getDiagnosticBinningJobDAO().findById(binningJobId);
            binningJob.setStatus(daoBean.getDiagnosticStatusTypeDAO().findById("Annotating variants"));
            daoBean.getDiagnosticBinningJobDAO().save(binningJob);
            logger.info(binningJob.toString());

            Executors.newSingleThreadExecutor().submit(new AnnotateVariantsCallable(daoBean, binningJob)).get();

            binningJob = daoBean.getDiagnosticBinningJobDAO().findById(binningJobId);
            binningJob.setStatus(daoBean.getDiagnosticStatusTypeDAO().findById("Annotated variants"));
            daoBean.getDiagnosticBinningJobDAO().save(binningJob);
            logger.info(binningJob.toString());

        } catch (Exception e) {
            try {
                DiagnosticBinningJob binningJob = daoBean.getDiagnosticBinningJobDAO().findById(binningJobId);
                binningJob.setStop(new Date());
                binningJob.setFailureMessage(e.getMessage());
                binningJob.setStatus(daoBean.getDiagnosticStatusTypeDAO().findById("Failed"));
                daoBean.getDiagnosticBinningJobDAO().save(binningJob);
                logger.info(binningJob.toString());
            } catch (CANVASDAOException e1) {
                logger.error(e1.getMessage(), e1);
            }
        }

    }

}
