/**
 * Copyright 2014 DuraSpace, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fcrepo.storage.policy;

import static java.util.Collections.singletonMap;
import static javax.jcr.nodetype.NodeType.MIX_MIMETYPE;
import static javax.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static javax.ws.rs.core.Response.created;
import static javax.ws.rs.core.Response.noContent;
import static javax.ws.rs.core.Response.ok;
import static org.apache.commons.lang.StringUtils.split;
import static org.slf4j.LoggerFactory.getLogger;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.nodetype.NoSuchNodeTypeException;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.NotAllowedException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import org.fcrepo.http.commons.AbstractResource;
import org.fcrepo.kernel.FedoraResource;
import org.fcrepo.kernel.services.policy.StoragePolicy;
import org.fcrepo.kernel.services.policy.StoragePolicyDecisionPoint;
import org.modeshape.jcr.api.JcrTools;
import org.slf4j.Logger;
import org.springframework.context.annotation.Scope;

import com.codahale.metrics.annotation.Timed;
import com.google.common.annotations.VisibleForTesting;

/**
 * RESTful interface to create and manage storage policies
 *
 *
 * @author osmandin
 * @since Aug 14, 2013
 */
@Scope("prototype")
@Path("/{path: .*}/fcr:storagepolicy")
public class FedoraStoragePolicy extends AbstractResource {

    public static final String FEDORA_STORAGE_POLICY_PATH = "/fedora:system/fedora:storage_policy";

    @Inject
    protected Session session;

    @Context
    protected HttpServletRequest request;

    @Inject
    protected StoragePolicyDecisionPoint storagePolicyDecisionPoint;

    private JcrTools jcrTools;

    public static final String POLICY_RESOURCE = "policies";

    private static final Logger LOGGER = getLogger(FedoraStoragePolicy.class);

    /**
     * Initialize
     *
     * @throws RepositoryException
     */
    @PostConstruct
    public void setUpRepositoryConfiguration() throws RepositoryException {
        Session internalSession = null;
        try {
            internalSession = sessions.getInternalSession();
            // we create a FedoraResource to initialize the storage of policies
            @SuppressWarnings("unused")
            final FedoraResource initializer
                    = objectService.findOrCreateObject(internalSession, FEDORA_STORAGE_POLICY_PATH);
            internalSession.save();
            LOGGER.debug("Created configuration node");
        } finally {
            if (internalSession != null) {
                internalSession.logout();
            }
        }
    }

    /**
     * POST to store nodeType and hint
     *
     * @param request For now, follows pattern: mix:mimeType image/tiff
     *        store-hint
     * @return status
     */

    @POST
    @Consumes(APPLICATION_FORM_URLENCODED)
    @Timed
    public Response post(final @PathParam("path") String path,
                         final String request) throws RepositoryException {
        LOGGER.debug("POST Received request param: {}", request);
        final Response.ResponseBuilder response;

        if (!path.equalsIgnoreCase(POLICY_RESOURCE)) {
            throw new NotAllowedException(
                    "POST method not allowed on " + getUriInfo().getAbsolutePath() +
                            ", try /policies/fcr:storagepolicy");
        }

        final String[] str = split(request); // simple split for now
        validateArgs(str.length);
        final Node node = getJcrTools().findOrCreateNode(session,
                FEDORA_STORAGE_POLICY_PATH, "test");
        if (isValidNodeTypeProperty(session, str[0]) ||
                isValidConfigurationProperty(str[0])) {
            node.setProperty(str[0], new String[]{str[1] + ":" + str[2]});

            // TODO (for now) instantiate PolicyType based on mix:mimeType
            final StoragePolicy policy = newPolicyInstance(str[0], str[1], str[2]);
            // TODO (for now) based on object comparison using equals()
            if (storagePolicyDecisionPoint.contains(policy)) {
                throw new StoragePolicyTypeException("Property already exists!");
            }
            storagePolicyDecisionPoint.add(policy);
            session.save();
            LOGGER.debug("Saved PDS hint {}", request);

            response = created(getUriInfo().getBaseUriBuilder()
                    .path(FedoraStoragePolicy.class)
                    .buildFromMap(singletonMap("path", str[0])));
        } else {
            throw new StoragePolicyTypeException(
                    "Invalid property type specified: " + str[0]);
        }


        return response.build();
    }

    /**
     * For nodeType n or runtime property p get {@link StoragePolicy}
     * implementation. Note: Signature might need to change, or a more
     * sophisticated method used, as implementation evolves.
     *
     * @param propertyType
     * @param itemType
     * @param value
     * @return a new StoragePolicy for the given property type
     * @throws StoragePolicyTypeException
     */
    protected StoragePolicy newPolicyInstance(final String propertyType,
        final String itemType, final String value) {

        switch (propertyType) {
            case MIX_MIMETYPE:
            case "mix:mimeType":
                return new MimeTypeStoragePolicy(itemType, value);
            default:
                throw new StoragePolicyTypeException("Mapping not found");
        }
    }

    /**
     * Delete NodeType. TODO for deleting multiple values with in a NodeType,
     * the design of how things are stored will need to change.
     * @return 204
     */
    @DELETE
    @Timed
    public Response deleteNodeType(@PathParam("path") final String nodeType)
        throws RepositoryException {
        LOGGER.debug("Deleting node property{}", nodeType);
        final Node node =
                getJcrTools().findOrCreateNode(session,
                        FEDORA_STORAGE_POLICY_PATH, "test");
        if (isValidNodeTypeProperty(session, nodeType)) {
            node.getProperty(nodeType).remove();
            session.save();

            // remove all MimeType intances (since thats only the stored
            // StoragePolicy for now.
            // TODO Once StoragePolicy is updated to display StoragePolicy type, this
            // would change
            storagePolicyDecisionPoint.clear();
            return noContent().build();
        }
        throw new RepositoryException(
                "Invalid property type specified.");
    }

    /**
     * TODO (for now) prints org.fcrepo.binary.StoragePolicyDecisionPointImpl
     *
     * @return response
     * @throws RepositoryException
     */
    @GET
    @Produces(APPLICATION_JSON)
    @Timed
    public Response get(final @PathParam("path") String path) throws RepositoryException {
        if (POLICY_RESOURCE.equalsIgnoreCase(path)) {
            return getAllStoragePolicies();
        }
        return getStoragePolicy(path);
    }

    private Response getAllStoragePolicies() {
        if (storagePolicyDecisionPoint == null ||
            storagePolicyDecisionPoint.isEmpty()) {
            return ok("No Policies Found").build();
        }
        return ok(storagePolicyDecisionPoint.toString()).build();
    }

    private Response getStoragePolicy(final String nodeType) throws RepositoryException {
        LOGGER.debug("Get storage policy for: {}", nodeType);
        Response.ResponseBuilder response;
        final Node node =
                getJcrTools().findOrCreateNode(session, FEDORA_STORAGE_POLICY_PATH, "test");

        final Property prop = node.getProperty(nodeType);
        if (null == prop) {
            throw new PathNotFoundException("StoragePolicy not found: " + nodeType);
        }

        final Value[] values = prop.getValues();
        if (values != null && values.length > 0) {
            response = ok(values[0].getString());
        } else {
            throw new PathNotFoundException("StoragePolicy not found: " + nodeType);
        }

        return response.build();
    }

    /**
     * Verifies whether node type is valid
     *
     * @param session
     * @param type
     * @return true if the node type is valid
     * @throws RepositoryException
     */
    private boolean isValidNodeTypeProperty(final Session session,
        final String type) throws RepositoryException {
        try {
            return session.getWorkspace().getNodeTypeManager()
                .getNodeType(type).getName().equals(type);
        } catch (final NoSuchNodeTypeException e) {
            LOGGER.debug("No corresponding Node type found for: {}", type, e);
            return false;
        }
    }

    /**
     * Consult some list of configuration of non JCR properties (e.g. list of
     * applicable runtime configurations)
     *
     * @return false
     * @throws StoragePolicyTypeException
     */
    private boolean isValidConfigurationProperty(@SuppressWarnings("unused") final String property) {
        // TODO (for now) returns false. For future, need to represent & eval.
        // non node type props
        return false;
    }

    /**
     * TODO (for now) Simple validation
     *
     * @param inputSize
     * @throws IllegalArgumentException
     */
    private void validateArgs(final int inputSize) {
        if (inputSize != InputPattern.valueOf(request.getMethod()).requiredLength) {
            throw new IllegalArgumentException("Invalid Arg");
        }
        // could do further checking here
    }

    private enum InputPattern {
        POST(3), DELETE(3);

        private final int requiredLength;

        private InputPattern(final int l) {
            requiredLength = l;
        }
    }

    private JcrTools getJcrTools() {
        if (null == jcrTools) {
            this.jcrTools = new JcrTools(true);
        }
        return jcrTools;
    }

    /**
     * Only for UNIT TESTING
     * @param jcrTools
     */
    @VisibleForTesting
    public void setJcrTools(final JcrTools jcrTools) {
        this.jcrTools = jcrTools;
    }

    private UriInfo getUriInfo() {
        return this.uriInfo;
    }

    /**
     * Only for UNIT TESTING
     * @param uriInfo
     */
    @VisibleForTesting
    public void setUriInfo(final UriInfo uriInfo) {
        this.uriInfo = uriInfo;
    }
}
