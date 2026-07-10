package com.vaultflow.auth.config;

import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.usertype.UserType;

import java.io.Serializable;
import java.sql.*;
import java.util.Objects;
import org.postgresql.util.PGobject;

/**
 * Custom Hibernate UserType for PostgreSQL INET columns.
 *
 * <p>Maps Java {@link String} to PostgreSQL {@code inet} type. This avoids changing the
 * database schema (which uses INET for {@code last_login_ip} and {@code ip_address}) while
 * keeping the Java entity fields as simple Strings.
 *
 * <p>PostgreSQL's JDBC driver returns INET values as {@link PGobject} with type "inet".
 * This type extracts the string representation for Java and converts back to a string
 * for storage.
 */
public class InetType implements UserType<String> {

  @Override
  public int getSqlType() {
    return Types.OTHER;
  }

  @Override
  public Class<String> returnedClass() {
    return String.class;
  }

  @Override
  public boolean equals(String x, String y) {
    return Objects.equals(x, y);
  }

  @Override
  public int hashCode(String x) {
    return Objects.hashCode(x);
  }

  @Override
  public String nullSafeGet(ResultSet rs, int position, SharedSessionContractImplementor session,
      Object owner) throws SQLException {
    Object value = rs.getObject(position);
    if (value == null) {
      return null;
    }
    // PostgreSQL JDBC driver returns INET as PGobject
    if (value instanceof PGobject) {
      return ((PGobject) value).getValue();
    }
    return value.toString();
  }

  @Override
  public void nullSafeSet(PreparedStatement st, String value, int index,
      SharedSessionContractImplementor session) throws SQLException {
    if (value == null) {
      st.setNull(index, Types.OTHER, "inet");
    } else {
      PGobject inetObject = new PGobject();
      inetObject.setType("inet");
      inetObject.setValue(value);
      st.setObject(index, inetObject);
    }
  }

  @Override
  public String deepCopy(String value) {
    return value;
  }

  @Override
  public boolean isMutable() {
    return false;
  }

  @Override
  public Serializable disassemble(String value) {
    return value;
  }

  @Override
  public String assemble(Serializable cached, Object owner) {
    return (String) cached;
  }
}