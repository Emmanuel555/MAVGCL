/****************************************************************************
 *
 *   Copyright (c) 2017 Eike Mansfeld ecm@gmx.de. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 ****************************************************************************/


package com.comino.flight.log;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.mavlink.messages.lquac.msg_log_data;
import org.mavlink.messages.lquac.msg_log_entry;
import org.mavlink.messages.lquac.msg_log_request_data;
import org.mavlink.messages.lquac.msg_log_request_list;

import com.comino.flight.file.FileHandler;
import com.comino.flight.log.ulog.UlogtoModelConverter;
import com.comino.flight.model.service.AnalysisModelService;
import com.comino.flight.observables.StateProperties;
import com.comino.mav.control.IMAVController;
import com.comino.msp.log.MSPLogger;
import com.comino.msp.main.control.listener.IMAVLinkListener;
import com.comino.msp.utils.ExecutorService;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import me.drton.jmavlib.log.ulog.ULogReader;

public class MavlinkLogReader implements IMAVLinkListener {

	private static final int LOG_PACKAG_DATA_LENGTH = 90;

	private static final int IDLE  = 0;
	private static final int ENTRY = 1;
	private static final int DATA  = 2;

	private int state = 0;

	private IMAVController control = null;

	private int      last_log_id  = 0;
	private long     log_size     = 0;
	private long     received_ms  = 0;
	private int      retry        = 0;
	private long     start        = 0;
	private long     time_utc     = 0;

	private int total_package_count = 0;

	private RandomAccessFile           file = null;

	private BooleanProperty            isCollecting    = null;
	private List<Long>                 unread_packages = null;
	private ScheduledFuture<?>         timeout;

	private String path    = null;


	private final StateProperties      props;
	private final MSPLogger            logger;
	private final AnalysisModelService modelService;
	private final FileHandler          fh;


	public MavlinkLogReader(IMAVController control) {
		this.control      = control;
		this.props        = StateProperties.getInstance();
		this.logger       = MSPLogger.getInstance();
		this.fh           = FileHandler.getInstance();
		this.modelService = AnalysisModelService.getInstance();
		this.isCollecting = new SimpleBooleanProperty();

		this.control.addMAVLinkListener(this);
	}

	public void requestLastLog() {
		try {
			this.path = fh.getTempFile().getPath();
			this.file = new RandomAccessFile(path, "rw");
		} catch (IOException e) {
			e.printStackTrace();
			return;
		}

		isCollecting.set(true); state = ENTRY; retry = 0;

		modelService.getModelList().clear();

		props.getProgressProperty().set(0);
		props.getLogLoadedProperty().set(false);

		timeout = ExecutorService.get().scheduleAtFixedRate(() -> {
			if((System.currentTimeMillis()-received_ms)>250) {

				switch(state) {
				case IDLE:
					timeout.cancel(true);
					break;
				case ENTRY:
					if(++retry > 3) {
						abortReadingLog();
						return;
					}
					requestLogList(0);
					break;
				case DATA:
					if(++retry > 5) {
						abortReadingLog();
						return;
					}
					int p = searchForNextUnreadPackage();
					if(p != -1)
						requestDataPackages(unread_packages.get(p),log_size);
					break;
				}
			}
		}, 200, 250, TimeUnit.MILLISECONDS);

		logger.writeLocalMsg("[mgc] Request latest log");
		start = System.currentTimeMillis();
		requestLogList(0);
	}

	public BooleanProperty isCollecting() {
		return isCollecting;
	}

	public void abortReadingLog() {
		try {
			file.close();
		} catch (IOException e) { e.printStackTrace(); }
		timeout.cancel(true); state = IDLE; isCollecting.set(false);
		props.getProgressProperty().set(StateProperties.NO_PROGRESS);
		logger.writeLocalMsg("[mgc] Abort reading log");
	}

	@Override
	public void received(Object o) {
			if( o instanceof msg_log_entry && isCollecting.get())
				handleLogEntry((msg_log_entry)o);

			if( o instanceof msg_log_data && isCollecting.get())
				handleLogData((msg_log_data) o);
	}

	private void handleLogEntry(msg_log_entry entry) {
		last_log_id = entry.num_logs - 1;
		received_ms = System.currentTimeMillis();
		if(last_log_id > -1) {
			if(entry.id != last_log_id)
				requestLogList(last_log_id);
			else {

				if(entry.size==0) {
					timeout.cancel(false); state = IDLE; isCollecting.set(false);
					return;
				}
				log_size = entry.size; time_utc = entry.time_utc;
				total_package_count = prepareUnreadPackageList(entry.size);
				System.out.println("Expected packages: "+unread_packages.size());
				logger.writeLocalMsg("[mgc] Importing Log ("+last_log_id+") - "+(entry.size/1024)+" kb");
				state = DATA;
				requestDataPackages(0,entry.size);

			}
		}
	}

	private void handleLogData(msg_log_data data) {
		received_ms = System.currentTimeMillis(); retry = 0;

		int p = getPackageNumber(data.ofs);

		if(p >= unread_packages.size()|| unread_packages.get(p)== -1)
			return;

		try {
			file.seek(data.ofs);
			for(int i=0;i<data.count;i++)
				file.write((byte)(data.data[i] & 0x00FF));
		} catch (IOException e) { return; }

		unread_packages.set(p, (long) -1);
		//System.out.println("Package: "+p +" -> "+unread_packages.get(p));

		int unread_count = getUnreadPackageCount();
		props.getProgressProperty().set(1.0f - (float)unread_count / total_package_count);
		if(unread_count==0) {
			timeout.cancel(false); state = IDLE;
			long speed = data.ofs * 1000 / ( 1024 * (System.currentTimeMillis() - start));
			try {
				file.close();
				ULogReader reader = new ULogReader(path);
				UlogtoModelConverter converter = new UlogtoModelConverter(reader,modelService.getModelList());
				converter.doConversion();
				reader.close();
			} catch (Exception e) { e.printStackTrace(); }
			isCollecting.set(false);
			logger.writeLocalMsg("[mgc] Import completed ("+speed+" kb/sec)");
			props.getLogLoadedProperty().set(true);
			fh.setName("Log-"+last_log_id+"-"+time_utc);
			props.getProgressProperty().set(StateProperties.NO_PROGRESS);
		}
	}

	private int searchForNextUnreadPackage() {
		for(int i=0;i<unread_packages.size();i++) {
			if(unread_packages.get(i)!= -1)
				return i;
		}
		return -1;
	}

	private int getUnreadPackageCount() {
		int c=0;
		for(int i=0;i<unread_packages.size();i++) {
			if(unread_packages.get(i)!= -1)
				c++;
		}
		return c;
	}

	private int prepareUnreadPackageList(long size) {
		unread_packages = new ArrayList<Long>();
		// TODO determine count of packages and fill list with offset
		int count = getPackageNumber(size);
		for(long i=0;i<count+1;i++)
			unread_packages.add(i * LOG_PACKAG_DATA_LENGTH);
		return count;
	}

	private int getPackageNumber(long offset) {
		return (int)(offset / LOG_PACKAG_DATA_LENGTH);
	}

	private void requestDataPackages(long offset, long len) {
		System.out.println("Request packages from: "+offset);
		msg_log_request_data msg = new msg_log_request_data(255,1);
		msg.target_component = 1;
		msg.target_system = 1;
		msg.id = last_log_id;
		msg.ofs   = offset;
		msg.count = len;
		control.sendMAVLinkMessage(msg);
	}

	private void requestLogList(int id) {
		msg_log_request_list msg = new msg_log_request_list(255,1);
		msg.target_component = 1;
		msg.target_system = 1;
		msg.start= id;
		msg.end = id;
		control.sendMAVLinkMessage(msg);
	}
}
