package com.axonlink.local;

import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

final class XmlFiles {
    private XmlFiles() {}

    static Graph.Issue issue(Path file, String rule, Exception error) {
        int line = error instanceof SAXParseException parse ? parse.getLineNumber() : -1;
        String reason = Graph.firstNonBlank(error.getMessage(), error.getClass().getSimpleName());
        return new Graph.Issue(null, file.toString(), line, rule, reason);
    }

    static Document read(Path file) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setExpandEntityReferences(false);
        if (Files.size(file) == 0L) {
            throw new EOFException("空 XML 文件");
        }
        try (InputStream in = Files.newInputStream(file)) {
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new ErrorHandler() {
                @Override public void warning(SAXParseException exception) throws SAXException { throw exception; }
                @Override public void error(SAXParseException exception) throws SAXException { throw exception; }
                @Override public void fatalError(SAXParseException exception) throws SAXException { throw exception; }
            });
            Document doc = builder.parse(in);
            doc.getDocumentElement().normalize();
            return doc;
        }
    }
}
