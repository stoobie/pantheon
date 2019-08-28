package com.redhat.pantheon.servlet;

import com.redhat.pantheon.asciidoctor.AsciidoctorPool;
import com.redhat.pantheon.asciidoctor.extension.MetadataExtractorTreeProcessor;
import com.redhat.pantheon.conf.GlobalConfig;
import com.redhat.pantheon.model.api.FileResource.JcrContent;
import com.redhat.pantheon.model.api.SlingResourceUtil;
import com.redhat.pantheon.model.module.Metadata;
import com.redhat.pantheon.model.module.Module;
import com.redhat.pantheon.model.module.ModuleRevision;
import org.apache.commons.lang3.LocaleUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.servlets.post.AbstractPostOperation;
import org.apache.sling.servlets.post.Modification;
import org.apache.sling.servlets.post.PostOperation;
import org.apache.sling.servlets.post.PostResponse;
import org.asciidoctor.Asciidoctor;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.google.common.collect.Maps.newHashMap;

/**
 * Post operation to add a new Module revision to the system.
 * Only thre parameters are expected in the post request:
 * 1. locale - Optional; indicates the locale that the module content is in
 * 2. :operation - This value must be 'pant:newModuleRevision'
 * 3. asciidoc - The file upload (multipart) containing the asciidoc content file for the new module revision.
 *
 * The url to POST a request to the server is the path of the new or existing module to host the content.
 * If there is no content for said url, the module is created and a single revision along with it.
 *
 * @author Carlos Munoz
 */
@Component(
        service = PostOperation.class,
        property = {
                Constants.SERVICE_DESCRIPTION + "=Servlet POST operation which accepts module uploads and versions them appropriately",
                Constants.SERVICE_VENDOR + "=Red Hat Content Tooling team",
                PostOperation.PROP_OPERATION_NAME + "=pant:newModuleRevision"
        })
public class ModuleRevisionUpload extends AbstractPostOperation {

    private static final Logger log = LoggerFactory.getLogger(ModuleRevisionUpload.class);

    private AsciidoctorPool asciidoctorPool;

    @Activate
    public ModuleRevisionUpload(@Reference AsciidoctorPool asciidoctorPool) {
        this.asciidoctorPool = asciidoctorPool;
    }

    @Override
    protected void doRun(SlingHttpServletRequest request, PostResponse response, List<Modification> changes) throws RepositoryException {

        try {
            String locale = ServletUtils.paramValue(request, "locale", GlobalConfig.DEFAULT_MODULE_LOCALE.toString());
            String asciidocContent = ServletUtils.paramValue(request, "asciidoc");
            String path = request.getResource().getPath();
            String moduleName = ResourceUtil.getName(path);
            String description = ServletUtils.paramValue(request, "jcr:description", "");

            log.debug("Pushing new module revision at: " + path + " with locale: " + locale);
            log.trace("and content: " + asciidocContent);

            // Try to find the module
            Resource moduleResource = request.getResourceResolver().getResource(path);
            Module module;

            if(moduleResource == null) {
                module =
                        SlingResourceUtil.createNewSlingResource(
                                request.getResourceResolver(),
                                path,
                                Module.class);
            } else {
                module = moduleResource.adaptTo(Module.class);
            }

            Locale localeObj = LocaleUtils.toLocale(locale);
            Optional<ModuleRevision> draftRevision = module.getDraftRevision(localeObj);
            // if there is no draft content, create it
            if( !draftRevision.isPresent() ) {
                draftRevision = Optional.of(
                        module.getOrCreateModuleLocale(localeObj)
                        .createNextRevision());
                module.getOrCreateModuleLocale(localeObj)
                        .draft.set( draftRevision.get().uuid.get() );
            }

            // modify only the draft content/metadata
            JcrContent jcrContent = draftRevision.get()
                    .content.getOrCreate()
                    .asciidoc.getOrCreate()
                    .jcrContent.getOrCreate();
            jcrContent.jcrData.set(asciidocContent);
            jcrContent.mimeType.set("text/x-asciidoc");

            Metadata metadata = draftRevision.get()
                    .metadata.getOrCreate();
            metadata.title.set(moduleName);
            metadata.description.set(description);

            extractMetadata(jcrContent, metadata);

            request.getResourceResolver().commit();

        } catch (Exception e) {
            throw new RepositoryException("Error uploading a module revision", e);
        }
    }

    private void extractMetadata(JcrContent content, Metadata metadata) {
        log.trace("=== Start extracting metadata ");
        long startTime = System.currentTimeMillis();
        Asciidoctor asciidoctor = asciidoctorPool.borrowObject();
        try {
            asciidoctor.javaExtensionRegistry().treeprocessor(
                    new MetadataExtractorTreeProcessor(metadata));

            asciidoctor.load(content.jcrData.get(), newHashMap());
        }
        finally {
            asciidoctorPool.returnObject(asciidoctor);
        }
        long endTime = System.currentTimeMillis();
        log.trace("=== End extracting metadata. Time lapsed: " + (endTime-startTime)/1000 + " secs");
    }

}
