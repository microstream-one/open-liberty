-include= ~${workspace}/cnf/resources/bnd/feature.props
symbolicName=io.openliberty.webCache1.0.internal.ee-6.0
singleton=true
WLP-DisableAllFeatures-OnConflict: false
visibility=private
-features=com.ibm.websphere.appserver.servlet-3.0; ibm.tolerates:="3.1, 4.0"
-bundles=\
  com.ibm.ws.dynacache.web
kind=ga
edition=core
