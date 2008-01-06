/*
// This software is subject to the terms of the Common Public License
// Agreement, available at the following URL:
// http://www.opensource.org/licenses/cpl.html.
// Copyright (C) 2007-2007 Julian Hyde
// All Rights Reserved.
// You must accept the terms of that agreement to use this software.
*/
package org.olap4j.driver.xmla;

import org.olap4j.*;
import org.olap4j.impl.Olap4jUtil;
import static org.olap4j.driver.xmla.XmlaOlap4jUtil.*;
import org.olap4j.metadata.*;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import java.io.*;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.*;
import java.sql.Date;
import java.util.*;

/**
 * Implementation of {@link org.olap4j.CellSet}
 * for XML/A providers.
 * 
 * <p>This class has sub-classes which implement JDBC 3.0 and JDBC 4.0 APIs;
 * it is instantiated using {@link Factory#newCellSet}.</p>
 *
 * @author jhyde
 * @version $Id$
 * @since May 24, 2007
 */
abstract class XmlaOlap4jCellSet implements CellSet {
    final XmlaOlap4jStatement olap4jStatement;
    protected boolean closed;
    private XmlaOlap4jCellSetMetaData metaData;
    private final Map<Integer, Cell> cellMap =
        new HashMap<Integer, Cell>();
    private final List<XmlaOlap4jCellSetAxis> axisList =
        new ArrayList<XmlaOlap4jCellSetAxis>();
    private final List<CellSetAxis> immutableAxisList =
        Olap4jUtil.cast(Collections.unmodifiableList(axisList));
    private XmlaOlap4jCellSetAxis filterAxis;
    private static final boolean DEBUG = true;

    private static final List<String> standardProperties = Arrays.asList(
        "UName", "Caption", "LName", "LNum", "DisplayInfo");

    XmlaOlap4jCellSet(
        XmlaOlap4jStatement olap4jStatement)
        throws OlapException
    {
        assert olap4jStatement != null;
        this.olap4jStatement = olap4jStatement;
        this.closed = false;
    }

    void populate() throws OlapException {
        byte[] bytes = olap4jStatement.getBytes();

        Document doc;
        try {
            doc = parse(bytes);
        } catch (IOException e) {
            throw olap4jStatement.olap4jConnection.helper.createException(
                "error creating CellSet", e);
        } catch (SAXException e) {
            throw olap4jStatement.olap4jConnection.helper.createException(
                "error creating CellSet", e);
        }
        // <SOAP-ENV:Envelope>
        //   <SOAP-ENV:Header/>
        //   <SOAP-ENV:Body>
        //     <xmla:ExecuteResponse>
        //       <xmla:return>
        //         <root>
        //           (see below)
        //         </root>
        //       <xmla:return>
        //     </xmla:ExecuteResponse>
        //   </SOAP-ENV:Body>
        // </SOAP-ENV:Envelope>
        final Element envelope = doc.getDocumentElement();
        if (DEBUG) System.out.println(XmlaOlap4jUtil.toString(doc,true));
        assert envelope.getLocalName().equals("Envelope");
        assert envelope.getNamespaceURI().equals(SOAP_NS);
        Element body =
            findChild(envelope, SOAP_NS, "Body");
        Element fault =
            findChild(body, SOAP_NS, "Fault");
        if (fault != null) {
            /*
            Example:
            
        <SOAP-ENV:Fault>
            <faultcode>SOAP-ENV:Client.00HSBC01</faultcode>
            <faultstring>XMLA connection datasource not found</faultstring>
            <faultactor>Mondrian</faultactor>
            <detail>
                <XA:error xmlns:XA="http://mondrian.sourceforge.net">
                    <code>00HSBC01</code>
                    <desc>The Mondrian XML: Mondrian Error:Internal
                        error: no catalog named 'LOCALDB'</desc>
                </XA:error>
            </detail>
        </SOAP-ENV:Fault>
             */
            // TODO: log doc to logfile
            final Element faultstring = findChild(fault, null, "faultstring");
            String message = faultstring.getTextContent();
            throw olap4jStatement.olap4jConnection.helper.createException(
                "XMLA provider gave exception: " + message);
        }
        Element executeResponse =
            findChild(body, XMLA_NS, "ExecuteResponse");
        Element returnElement =
            findChild(executeResponse, XMLA_NS, "return");
        // <root> has children
        //   <xsd:schema/>
        //   <OlapInfo>
        //     <CubeInfo>
        //       <Cube>
        //         <CubeName>FOO</CubeName>
        //       </Cube>
        //     </CubeInfo>
        //     <AxesInfo>
        //       <AxisInfo/> ...
        //     </AxesInfo>
        //   </OlapInfo>
        //   <Axes>
        //      <Axis>
        //        <Tuples>
        //      </Axis>
        //      ...
        //   </Axes>
        //   <CellData>
        //      <Cell/>
        //      ...
        //   </CellData>
        final Element root =
            findChild(returnElement, MDDATASET_NS, "root");

        if (olap4jStatement instanceof XmlaOlap4jPreparedStatement) {
            this.metaData =
                ((XmlaOlap4jPreparedStatement) olap4jStatement)
                    .cellSetMetaData;
        } else {
            this.metaData = createMetaData(olap4jStatement, root);
        }

        // todo: use CellInfo element to determine mapping of cell properties
        // to XML tags
        /*
                        <CellInfo>
                            <Value name="VALUE"/>
                            <FmtValue name="FORMATTED_VALUE"/>
                            <FormatString name="FORMAT_STRING"/>
                        </CellInfo>
         */

        final Element axesNode = findChild(root, MDDATASET_NS, "Axes");
        for (Element axisNode : findChildren(axesNode, MDDATASET_NS, "Axis")) {
            final String axisName = axisNode.getAttribute("name");
            final Axis axis = xx(axisName);
            final XmlaOlap4jCellSetAxis cellSetAxis =
                new XmlaOlap4jCellSetAxis(this, axis);
            switch (axis) {
            case FILTER:
                filterAxis = cellSetAxis;
                break;
            default:
                axisList.add(cellSetAxis);
                break;
            }
            final Element tuplesNode =
                findChild(axisNode, MDDATASET_NS, "Tuples");
            int ordinal = 0;
            final Map<Property, String> propertyValues =
                new HashMap<Property, String>();
            for (Element tupleNode
                : findChildren(tuplesNode, MDDATASET_NS, "Tuple"))
            {
                final List<Member> members = new ArrayList<Member>();
                for (Element memberNode
                    : findChildren(tupleNode, MDDATASET_NS, "Member"))
                {
                    String hierarchyName =
                        memberNode.getAttribute("Hierarchy");
                    String uname = stringElement(memberNode, "UName");
                    Member member =
                        metaData.cube.lookupMemberByUniqueName(uname);
                    propertyValues.clear();
                    for (Element childNode : childElements(memberNode)) {
                        XmlaOlap4jCellSetMemberProperty property =
                            ((XmlaOlap4jCellSetAxisMetaData)
                                cellSetAxis.getAxisMetaData()).lookupProperty(
                                hierarchyName,
                                childNode.getLocalName());
                        if (property != null) {
                            String value = childNode.getTextContent();
                            propertyValues.put(property, value);
                        }
                    }
                    if (!propertyValues.isEmpty()) {
                        member =
                            new XmlaOlap4jPositionMember(
                                member, propertyValues);
                    }
                    members.add(member);
                }
                cellSetAxis.positions.add(
                    new XmlaOlap4jPosition(members, ordinal++));
            }
        }

        final Map<Property, Object> propertyValues =
            new HashMap<Property, Object>();
        final Element cellDataNode = findChild(root, MDDATASET_NS, "CellData");
        for (Element cell : findChildren(cellDataNode, MDDATASET_NS, "Cell")) {
            propertyValues.clear();
            final int cellOrdinal =
                Integer.valueOf(cell.getAttribute("CellOrdinal"));
            // todo: convert to type based on <Value xsi:type> attribute
            final String value = stringElement(cell, "Value");
            final String formattedValue = stringElement(cell, "FmtValue");
            final String formatString = stringElement(cell, "FormatString");
            for (Element element : childElements(cell)) {
                String tag = element.getLocalName();
                final Property property =
                    metaData.propertiesByTag.get(tag);
                if (property != null) {
                    propertyValues.put(property, element.getTextContent());
                }
            }
            cellMap.put(
                cellOrdinal,
                new XmlaOlap4jCell(
                    this,
                    cellOrdinal,
                    value,
                    formattedValue,
                    propertyValues));
        }
    }

    private XmlaOlap4jCellSetMetaData createMetaData(
        XmlaOlap4jStatement olap4jStatement,
        Element root) throws OlapException
    {
        final Element olapInfo =
            findChild(root, MDDATASET_NS, "OlapInfo");
        final Element cubeInfo =
            findChild(olapInfo, MDDATASET_NS, "CubeInfo");
        final Element cubeNode =
            findChild(cubeInfo, MDDATASET_NS, "Cube");
        final Element cubeNameNode =
            findChild(cubeNode, MDDATASET_NS, "CubeName");
        final String cubeName = gatherText(cubeNameNode);
        final XmlaOlap4jCube cube =
            this.olap4jStatement.olap4jConnection.olap4jSchema.cubes.get(
                cubeName);
        if (cube == null) {
            throw olap4jStatement.olap4jConnection.helper.createException(
                "Internal error: cube '" + cubeName + "' not found");
        }
        final Element axesInfo =
            findChild(olapInfo, MDDATASET_NS, "AxesInfo");
        final List<Element> axisInfos =
            findChildren(axesInfo, MDDATASET_NS, "AxisInfo");
        final List<CellSetAxisMetaData> axisMetaDataList =
            new ArrayList<CellSetAxisMetaData>();
        XmlaOlap4jCellSetAxisMetaData filterAxisMetaData = null;
        for (Element axisInfo : axisInfos) {
            final String axisName = axisInfo.getAttribute("name");
            Axis axis = xx(axisName);
            final List<Element> hierarchyInfos =
                findChildren(axisInfo, MDDATASET_NS, "HierarchyInfo");
            final List<Hierarchy> hierarchyList =
                new ArrayList<Hierarchy>();
            /*
            <OlapInfo>
                <AxesInfo>
                    <AxisInfo name="Axis0">
                        <HierarchyInfo name="Customers">
                            <UName name="[Customers].[MEMBER_UNIQUE_NAME]"/>
                            <Caption name="[Customers].[MEMBER_CAPTION]"/>
                            <LName name="[Customers].[LEVEL_UNIQUE_NAME]"/>
                            <LNum name="[Customers].[LEVEL_NUMBER]"/>
                            <DisplayInfo name="[Customers].[DISPLAY_INFO]"/>
                        </HierarchyInfo>
                    </AxisInfo>
                    ...
                </AxesInfo>
                <CellInfo>
                    <Value name="VALUE"/>
                    <FmtValue name="FORMATTED_VALUE"/>
                    <FormatString name="FORMAT_STRING"/>
                </CellInfo>
            </OlapInfo>
             */
            final List<XmlaOlap4jCellSetMemberProperty> propertyList =
                new ArrayList<XmlaOlap4jCellSetMemberProperty>();
            for (Element hierarchyInfo : hierarchyInfos) {
                final String hierarchyName =
                    hierarchyInfo.getAttribute("name");
                final Hierarchy hierarchy =
                    cube.getHierarchies().get(hierarchyName);
                if (hierarchy == null) {
                    throw olap4jStatement.olap4jConnection.helper.createException(
                        "Internal error: hierarchy '" + hierarchyName
                            + "' not found in cube '" + cubeName + "'");
                }
                hierarchyList.add(hierarchy);
                for (Element childNode : childElements(hierarchyInfo)) {
                    String tag = childNode.getLocalName();
                    if (standardProperties.contains(tag)) {
                        continue;
                    }
                    final String propertyUniqueName =
                        childNode.getAttribute("name");
                    final XmlaOlap4jCellSetMemberProperty property =
                        new XmlaOlap4jCellSetMemberProperty(
                            propertyUniqueName,
                            hierarchy,
                            tag);
                    propertyList.add(property);
                }
            }
            final XmlaOlap4jCellSetAxisMetaData axisMetaData =
                new XmlaOlap4jCellSetAxisMetaData(
                    olap4jStatement.olap4jConnection,
                    axis,
                    hierarchyList,
                    propertyList);
            switch (axis) {
            case FILTER:
                filterAxisMetaData = axisMetaData;
                break;
            default:
                axisMetaDataList.add(axisMetaData);
                break;
            }
        }
        final Element cellInfo =
            findChild(olapInfo, MDDATASET_NS, "CellInfo");
        List<XmlaOlap4jCellProperty> cellProperties =
            new ArrayList<XmlaOlap4jCellProperty>();
        for (Element element : childElements(cellInfo)) {
            cellProperties.add(
                new XmlaOlap4jCellProperty(
                    element.getLocalName(),
                    element.getAttribute("name")));
        }
        return
            new XmlaOlap4jCellSetMetaData(
                olap4jStatement,
                cube,
                filterAxisMetaData,
                axisMetaDataList,
                cellProperties);
    }

    private Axis xx(String axisName) {
        Axis axis;
        if (axisName.startsWith("Axis")) {
            final Integer ordinal =
                Integer.valueOf(axisName.substring("Axis".length()));
            axis = Axis.values()[Axis.COLUMNS.ordinal() + ordinal];
        } else {
            axis = Axis.FILTER;
        }
        return axis;
    }

    public CellSetMetaData getMetaData() {
        return metaData;
    }

    public Cell getCell(List<Integer> coordinates) {
        return getCellInternal(coordinatesToOrdinal(coordinates));
    }

    public Cell getCell(int ordinal) {
        return getCellInternal(ordinal);
    }

    public Cell getCell(Position... positions) {
        if (positions.length != getAxes().size()) {
            throw new IllegalArgumentException(
                "cell coordinates should have dimension " + getAxes().size());
        }
        List<Integer> coords = new ArrayList<Integer>(positions.length);
        for (Position position : positions) {
            coords.add(position.getOrdinal());
        }
        return getCell(coords);
    }

    private Cell getCellInternal(int pos) {
        final Cell cell = cellMap.get(pos);
        if (cell == null) {
            if (pos < 0 || pos >= maxOrdinal()) {
                throw new IndexOutOfBoundsException();
            } else {
                // Cell is within bounds, but is not held in the cache because
                // it has no value. Manufacture a cell with an empty value.
                return new XmlaOlap4jCell(
                    this, pos, null, null,
                    Collections.<Property, Object>emptyMap());
            }
        }
        return cell;
    }

    private String getBoundsAsString() {
        StringBuilder buf = new StringBuilder();
        int k = 0;
        for (CellSetAxis axis : getAxes()) {
            if (k++ > 0) {
                buf.append(", ");
            }
            buf.append(axis.getPositionCount());
        }
        return buf.toString();
    }

    public List<CellSetAxis> getAxes() {
        return immutableAxisList;
    }

    public CellSetAxis getFilterAxis() {
        return filterAxis;
    }

    private int maxOrdinal() {
        int modulo = 1;
        for (CellSetAxis axis : axisList) {
            modulo *= axis.getPositionCount();
        }
        return modulo;
    }

    public List<Integer> ordinalToCoordinates(int ordinal) {
        List<CellSetAxis> axes = getAxes();
        final List<Integer> list = new ArrayList<Integer>(axes.size());
        int modulo = 1;
        for (CellSetAxis axis : axes) {
            int prevModulo = modulo;
            modulo *= axis.getPositionCount();
            list.add((ordinal % modulo) / prevModulo);
        }
        if (ordinal < 0 || ordinal >= modulo) {
            throw new IndexOutOfBoundsException(
                "Cell ordinal " + ordinal
                    + ") lies outside CellSet bounds ("
                    + getBoundsAsString() + ")");
        }
        return list;
    }

    public int coordinatesToOrdinal(List<Integer> coordinates) {
        List<CellSetAxis> axes = getAxes();
        if (coordinates.size() != axes.size()) {
            throw new IllegalArgumentException(
                "Coordinates have different dimension " + coordinates.size()
                    + " than axes " + axes.size());
        }
        int modulo = 1;
        int ordinal = 0;
        int k = 0;
        for (CellSetAxis axis : axes) {
            final Integer coordinate = coordinates.get(k++);
            if (coordinate < 0 || coordinate >= axis.getPositionCount()) {
                throw new IndexOutOfBoundsException(
                    "Coordinate " + coordinate
                        + " of axis " + k
                        + " is out of range ("
                        + getBoundsAsString() + ")");
            }
            ordinal += coordinate * modulo;
            modulo *= axis.getPositionCount();
        }
        return ordinal;
    }

    public boolean next() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void close() throws SQLException {
        this.closed = true;
    }

    public boolean wasNull() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public String getString(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public boolean getBoolean(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public byte getByte(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public short getShort(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public int getInt(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public long getLong(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public float getFloat(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public double getDouble(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public BigDecimal getBigDecimal(
        int columnIndex, int scale) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public byte[] getBytes(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Date getDate(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Time getTime(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Timestamp getTimestamp(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public InputStream getAsciiStream(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public InputStream getUnicodeStream(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public InputStream getBinaryStream(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public String getString(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public boolean getBoolean(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public byte getByte(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public short getShort(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public int getInt(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public long getLong(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public float getFloat(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public double getDouble(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public BigDecimal getBigDecimal(
        String columnLabel, int scale) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public byte[] getBytes(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Date getDate(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Time getTime(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Timestamp getTimestamp(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public InputStream getAsciiStream(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public InputStream getUnicodeStream(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public InputStream getBinaryStream(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public SQLWarning getWarnings() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void clearWarnings() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public String getCursorName() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Object getObject(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Object getObject(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public int findColumn(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Reader getCharacterStream(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Reader getCharacterStream(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public BigDecimal getBigDecimal(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public BigDecimal getBigDecimal(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public boolean isBeforeFirst() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public boolean isAfterLast() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public boolean isFirst() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public boolean isLast() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void beforeFirst() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void afterLast() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public boolean first() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public boolean last() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public int getRow() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public boolean absolute(int row) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public boolean relative(int rows) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public boolean previous() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void setFetchDirection(int direction) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public int getFetchDirection() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void setFetchSize(int rows) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public int getFetchSize() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public int getType() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public int getConcurrency() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public boolean rowUpdated() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public boolean rowInserted() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public boolean rowDeleted() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateNull(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateBoolean(int columnIndex, boolean x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateByte(int columnIndex, byte x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateShort(int columnIndex, short x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateInt(int columnIndex, int x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateLong(int columnIndex, long x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateFloat(int columnIndex, float x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateDouble(int columnIndex, double x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateBigDecimal(
        int columnIndex, BigDecimal x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateString(int columnIndex, String x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateBytes(int columnIndex, byte x[]) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateDate(int columnIndex, Date x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateTime(int columnIndex, Time x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateTimestamp(
        int columnIndex, Timestamp x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateAsciiStream(
        int columnIndex, InputStream x, int length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateBinaryStream(
        int columnIndex, InputStream x, int length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateCharacterStream(
        int columnIndex, Reader x, int length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateObject(
        int columnIndex, Object x, int scaleOrLength) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateObject(int columnIndex, Object x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateNull(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateBoolean(
        String columnLabel, boolean x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateByte(String columnLabel, byte x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateShort(String columnLabel, short x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateInt(String columnLabel, int x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateLong(String columnLabel, long x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateFloat(String columnLabel, float x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateDouble(String columnLabel, double x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateBigDecimal(
        String columnLabel, BigDecimal x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateString(String columnLabel, String x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateBytes(String columnLabel, byte x[]) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateDate(String columnLabel, Date x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateTime(String columnLabel, Time x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateTimestamp(
        String columnLabel, Timestamp x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateAsciiStream(
        String columnLabel, InputStream x, int length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateBinaryStream(
        String columnLabel, InputStream x, int length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateCharacterStream(
        String columnLabel, Reader reader, int length) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateObject(
        String columnLabel, Object x, int scaleOrLength) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateObject(String columnLabel, Object x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void insertRow() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateRow() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void deleteRow() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void refreshRow() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void cancelRowUpdates() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void moveToInsertRow() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void moveToCurrentRow() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Statement getStatement() throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Object getObject(
        int columnIndex, Map<String, Class<?>> map) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Ref getRef(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Blob getBlob(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Clob getClob(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Array getArray(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Object getObject(
        String columnLabel, Map<String, Class<?>> map) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Ref getRef(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Blob getBlob(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Clob getClob(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Array getArray(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Date getDate(int columnIndex, Calendar cal) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Date getDate(String columnLabel, Calendar cal) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Time getTime(int columnIndex, Calendar cal) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Time getTime(String columnLabel, Calendar cal) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Timestamp getTimestamp(
        int columnIndex, Calendar cal) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public Timestamp getTimestamp(
        String columnLabel, Calendar cal) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public URL getURL(int columnIndex) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public URL getURL(String columnLabel) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateRef(int columnIndex, Ref x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateRef(String columnLabel, Ref x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateBlob(int columnIndex, Blob x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateBlob(String columnLabel, Blob x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateClob(int columnIndex, Clob x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateClob(String columnLabel, Clob x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateArray(int columnIndex, Array x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public void updateArray(String columnLabel, Array x) throws SQLException {
        throw new UnsupportedOperationException();
    }

    // implement Wrapper

    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new UnsupportedOperationException();
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        throw new UnsupportedOperationException();
    }

}

// End XmlaOlap4jCellSet.java