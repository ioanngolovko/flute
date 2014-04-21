/*
   (с) 2013 ООО "КУРС-ИТ"  

   Этот файл — часть КУРС:Flute.
   
   КУРС:Flute — свободная программа: вы можете перераспространять ее и/или изменять
   ее на условиях Стандартной общественной лицензии GNU в том виде, в каком
   она была опубликована Фондом свободного программного обеспечения; либо
   версии 3 лицензии, либо (по вашему выбору) любой более поздней версии.

   Эта программа распространяется в надежде, что она будет полезной,
   но БЕЗО ВСЯКИХ ГАРАНТИЙ; даже без неявной гарантии ТОВАРНОГО ВИДА
   или ПРИГОДНОСТИ ДЛЯ ОПРЕДЕЛЕННЫХ ЦЕЛЕЙ. Подробнее см. в Стандартной
   общественной лицензии GNU.

   Вы должны были получить копию Стандартной общественной лицензии GNU
   вместе с этой программой. Если это не так, см. http://www.gnu.org/licenses/.

   
   Copyright 2013, COURSE-IT Ltd.

   This program is free software: you can redistribute it and/or modify
   it under the terms of the GNU General Public License as published by
   the Free Software Foundation, either version 3 of the License, or
   (at your option) any later version.

   This program is distributed in the hope that it will be useful,
   but WITHOUT ANY WARRANTY; without even the implied warranty of
   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
   GNU General Public License for more details.
   You should have received a copy of the GNU General Public License
   along with this program.  If not, see http://www.gnu.org/licenses/.

 */
package ru.curs.flute;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Level;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.python.core.JavaImporter;
import org.python.core.Py;
import org.python.core.PyException;
import org.python.core.PyInteger;
import org.python.core.PyObject;
import org.python.core.PyString;
import org.python.util.PythonInterpreter;

/**
 * Класс обработчика заданий. Одновременно запускается несколько обработчиков.
 * 
 */
public abstract class PythonProcessor extends Thread {

	private TaskParams task;
	private Connection conn;

	@Override
	public void run() {
		try {
			initConn();
			String message = internalRun();
			finish(true, message == null ? "" : message);
		} catch (EFluteRuntime e) {
			finish(false, e.getMessage());
		}

	}

	String internalRun() throws EFluteRuntime {
		final File f = new File(AppSettings.getScriptsPath() + File.separator
				+ task.getScriptName());
		if (!f.exists())
			throw new EFluteRuntime("Script file " + f + " does not exist!");
		if (!f.canRead())
			throw new EFluteRuntime("Script file " + f + " cannot be read!");
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(f);
		} catch (FileNotFoundException e) {
			// This should not happen here.
			e.printStackTrace();
		}

		StringWriter sw = new StringWriter();
		if (task.getParams() != null)
			try {
				Transformer tr = javax.xml.transform.TransformerFactory
						.newInstance().newTransformer();
				tr.setOutputProperty(OutputKeys.ENCODING, "UTF-16");

				tr.transform(new DOMSource(task.getParams()), new StreamResult(
						sw));
			} catch (TransformerException e) {
				throw new EFluteRuntime(
						"Error while processing XML parameters: "
								+ e.getMessage() + " for task " + task.getId());
			}
		else if (task.getStrParams() != null)
			sw.append(task.getStrParams());

		PythonInterpreter interp = new PythonInterpreter();
		interp.set("repair", new JavaImporter() {
			private static final long serialVersionUID = 1L;

			@Override
			// CHECKSTYLE:OFF
			public PyObject __call__(PyObject[] args, String[] keywords) {
				// CHECKSTYLE:ON
				Connection c = Py.tojava(args[0], Connection.class);
				try {
					return Py.java2py(ConnectionPool.repair(c));
				} catch (EFluteCritical e) {
					throw new PyException(Py.java2py(e));
				}
			}
		});
		interp.set("taskid", new PyInteger(task.getId()));
		interp.set("params", new PyString(sw.toString()));
		interp.set("conn", conn);
		interp.set("resultstream", task.getOutStream());
		try {
			interp.execfile(fis);
			PyObject message = interp.get("message");
			return message == null ? null : message.asString();
		} catch (PyException e) {
			throw new EFluteRuntime(String.format("Python error: %s:%s",
					e.type, e.value));
		}
	}

	/**
	 * Завершение процедуры обработки.
	 * 
	 * @param success
	 *            true, если завершилось без ошибок.
	 * @param details
	 *            Строка с дополнительным сообщением --- например, с деталями
	 *            произошедшей ошибки.
	 */
	private void finish(boolean success, String details) {

		PreparedStatement finalizeTaskStmt;

		try {
			// На случай, если коннекшн испортился после прогонки
			// скрипта
			initConn();

			finalizeTaskStmt = conn
					.prepareStatement(String
							.format("UPDATE %s SET STATUS = ?, result = ?, errortext = ? WHERE ID = ?",
									AppSettings.getTableName()));

			finalizeTaskStmt.setInt(1, success ? 2 : 3);

			if (task.getBufferLength() == 0) {
				switch (AppSettings.getDBType()) {
				case MSSQL:
					finalizeTaskStmt.setNull(2, java.sql.Types.BLOB);
					break;
				case POSTGRES:
					finalizeTaskStmt.setNull(2, java.sql.Types.VARBINARY);
					break;
				default:
					finalizeTaskStmt.setNull(2, java.sql.Types.VARBINARY);
				}
			} else {
				switch (AppSettings.getDBType()) {
				case MSSQL:
					finalizeTaskStmt.setBinaryStream(
							2,
							new ByteArrayInputStream(task.getBuffer(), 0, task
									.getBufferLength()));
					break;
				case POSTGRES:
					finalizeTaskStmt
							.setBinaryStream(2,
									new ByteArrayInputStream(task.getBuffer(),
											0, task.getBufferLength()), task
											.getBufferLength());
					break;
				default:
					finalizeTaskStmt.setBinaryStream(
							2,
							new ByteArrayInputStream(task.getBuffer(), 0, task
									.getBufferLength()));
				}
			}

			finalizeTaskStmt.setString(3, details);
			finalizeTaskStmt.setInt(4, task.getId());
			finalizeTaskStmt.execute();
			if (!conn.getAutoCommit())
				conn.commit();

			if (!success)
				AppSettings
						.getLogger()
						.log(Level.WARNING,
								String.format(
										"Task %d for template '%s' failed with message: %s\n",
										task.getId(), task.getScriptName(),
										details));
		} catch (SQLException | EFluteRuntime e) {
			// Перевыбросить эксепшн в этом контексте сделать нельзя...
			AppSettings.getLogger()
					.log(Level.SEVERE,
							"Could not finalize task with exception: "
									+ e.getMessage());
		}

		ConnectionPool.putBack(conn);
		finish();
	}

	/**
	 * Завершение процедуры обработки.
	 */
	protected abstract void finish();

	/**
	 * Устанавливает параметры задания.
	 * 
	 * @param currentTask
	 *            Параметры задания
	 */
	public void setTask(TaskParams currentTask) {
		task = currentTask;
	}

	/**
	 * Возвращает параметры задания.
	 */
	final TaskParams getTask() {
		return task;
	}

	private void initConn() throws EFluteRuntime {
		try {
			if (conn == null || conn.isClosed())
				conn = ConnectionPool.get();
		} catch (SQLException e) {
			throw new EFluteRuntime("Could not connect to "
					+ AppSettings.getDatabaseConnection() + "with error: "
					+ e.getMessage());
		} catch (EFluteCritical e) {
			throw new EFluteRuntime(e.getMessage());
		}
	}

}
