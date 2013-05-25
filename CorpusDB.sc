//This file is part of cbpsc (last revision @ version 1.0).
//
//cbpsc is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
//
//cbpsc is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
//
//You should have received a copy of the GNU General Public License along with cbpsc.  If not, see <http://www.gnu.org/licenses/>.
//
// cbpsc : created by Tom Stoll : tms@kitefishlabs.com : www.kitefishlabs.com
//
// CorpusDB.sc
// Copyright (C) 2010-2013, Thomas Stoll

CorpusDB {

	var <>anchor, <>server, <>rate, <>hopSeconds, <>hopMS, <>sfTree, <>segTable, <>cuTable, <>rawTable, <>rawMaps, <>powers, <>mfccs, <>activationLayers, <>cookedLayers, <>sfMap, <>sfgMap, <>tagMap, <>transformations, <>synthdefs, <>sfOffset, <>cuOffset, <>dTable, <>soundFileUnitsMapped;

	*new { |anchor, server, hopMS=40|
		^super.new.initCorpusDB(anchor, server, hopMS)
	}

	initCorpusDB { |anchor, server, hopMS|

		this.anchor = anchor;
		this.server = server;
		this.hopMS = hopMS;
		this.hopSeconds = hopMS * 0.001;
		this.resetCorpus;
		^this
	}

	resetCorpus {

		this.sfTree = SFTree.new(this, this.anchor);
//  		this.segTable = Dictionary[]; // segtable's role is now part of the sftree nodes!
		this.cuTable = Dictionary[];
		// data structures for raw data and corpus data
		// this.rawTable = Dictionary[];
		// this.rawMaps = Dictionary[];
		this.powers = Dictionary[];
		this.mfccs = Dictionary[];
		this.activationLayers = Dictionary[];
		this.cookedLayers = Dictionary[];
		// corpus-level mappings and helper data structures
		this.sfMap = Dictionary[];
		this.sfgMap = Dictionary[];
		this.tagMap = Dictionary[];
		this.transformations = Dictionary[0 -> \thru, \thru -> 0];
		this.synthdefs = Dictionary[];
		// information about the corpus's current state
		this.sfOffset = 0;
		this.cuOffset = 0;
		//this.sfgSet = Set[]; // to-do: make this work: the set has to be valid after every change to cutable & segtable
		this.dTable = Dictionary[0 -> \unitID, 1 -> \sfRelID, 2 -> \sfileID, 3 -> \sfGrpID,
			4 -> \onset, 5 -> \duration, 6 -> \tRatio, 7 -> \uTag];
		this.soundFileUnitsMapped = false;
	}

	// pass sfID as nil to let CorpusDB to choose an id for you! (useful in batch mode.)
	addSoundFile { |filePath=nil, sfid=nil, srcFileID=nil, tRatio=1.0, sfGrpID=0, synthdef=nil, params=nil, subdir=nil, reuseFlag=nil, importFlag=nil, uflag=nil|

		var sfID, rootNode, childNode, joinedPath;
		Post << "add sound file sfid arg: " << sfid << "\n";
		(sfid.isNil).if {
			"auto-assigning sfID".postln;
			sfID = this.sfOffset;
			this.sfOffset = sfID + 1;
		} {
			sfID = sfid;
		};

		(srcFileID.isNil).if {

			rootNode = this.sfTree.addRootNode(filePath, sfID, tRatio, sfGrpID, uniqueFlag:uflag);
			(verbose.isNil.not).if {
				Post << "FILEPATH: " << filePath << "\n";
				Post << "addRootNode result: ";
				Post << rootNode.sfPath << ", " << rootNode.sfID << ", " << rootNode.group << ", " << rootNode.tRatio << "\n";
			};
			(importFlag.isNil.not).if { this.importSoundFileToBuffer(rootNode.sfPath, sfID) };

			^rootNode

		} {

			childNode = this.sfTree.addChildNode(srcFileID, sfID, tRatio, sfGrpID, synthdef, params, uniqueFlag:uflag);
			(verbose.isNil.not).if {
				Post << "\n" << [srcFileID, sfID] << "\n";
				Post << "addChildNode result: " << childNode.parentID << ", " << childNode.sfID << ", " << childNode.group << ", " << childNode.tRatio << "\n";
			};
			^childNode

		};
	}

	removeSoundFile {|filePath| ^nil }

	importSoundFileToBuffer { |path, sfid| ^nil }

	analyzeSoundFile { |sfID, group=0, tRatio=1.0, subdir=nil, verbose=nil|

		var filepath, fullpath, dir, mdpath, file, pBuf, aBuf, sFile, oscList, srows, prows;
		var timeout = 999, res = 0, thebuffer, ary, timeoffset = 0;
		var currBus = 20, tbDur, tbTRatio, tbSynthdefs, tbParams, parentID;
		var done = 0;

		filepath = this.lookupPath(sfID);
		// pathname as a Pathname object; extract dir, file, and full path as Strings
		fullpath = PathName.new(filepath);
		dir = fullpath.pathOnly.asString;
		file = fullpath.fileNameWithoutExtension.asString;
		fullpath = fullpath.fullPath.asString;
		// execute a command in the terminal .. this will not make a new md dir if it is already there!
		Pipe.new("cd " ++ dir.asString ++ "; mkdir md", "w").close;

		mdpath = dir.asString +/+ "md" +/+ file ++ "." ++ tRatio ++ ".md.wav";

		sFile = SoundFile.new; sFile.openRead(fullpath); sFile.close;

		pBuf = this.server.bufferAllocator.alloc(1);
		aBuf = this.server.bufferAllocator.alloc(1);

		(verbose.isNil.not).if {
			Post << "RMDDIR: " << mdpath << "\n" << tRatio.class << "\n";
			Post << "Dur: " << sFile.duration << "\n";
			Post << "pairs: " << [pBuf, aBuf] << "\n";
		};

		TempoClock.default.tempo = 1;
		oscList = [[0.0, [\b_allocReadChannel, pBuf, fullpath, 0, -1, [0]]]];
		oscList = oscList ++ [[0.01, [\b_alloc, aBuf, ((sFile.duration / this.hopSeconds) / tRatio).ceil, 25] ]];

		(this.sfTree.nodes[sfID].class == SamplerNode).if {

			tbDur = this.sfTree.trackbacks[sfID][1];
			tbTRatio = this.sfTree.trackbacks[sfID][2];
			tbSynthdefs = [this.sfTree.trackbacks[sfID][3].asSymbol];
		} {

			parentID = this.sfTree.nodes[sfID].parentID;
			tbDur = this.sfTree.trackbacks[parentID][1];
			tbTRatio = this.sfTree.trackbacks[parentID][2];
			tbSynthdefs = [this.sfTree.trackbacks[parentID][3].asSymbol, this.sfTree.trackbacks[sfID][0].asSymbol];
			// tbSynthdefs.postln;
			tbParams = this.sfTree.trackbacks[sfID][1];

		};

		(verbose.isNil.not).if {
			Post << this.sfTree.trackbacks[sfID][0] << "\n"; // path
			Post << fullpath << "\n";
			Post << tbDur << "\n"; // duration
			Post << sFile.duration << "\n";
			Post << tbTRatio << "\n"; // tRatio should == tbTRatio
			Post << tRatio << "\n";
			Post << tbSynthdefs << "\n"; // synthdef name
			Post << this.sfTree.nodes[sfID] << "\n";
		};


		srows = [tbSynthdefs,  \mfcc24BusAnalyzerNRT].flat;
		prows = [[\srcbufNum, pBuf, \outbus, 0, \start, 0, \dur, tbDur, \transp, tbTRatio], [\inbus, 0, \savebufNum, aBuf, \transp, tbTRatio]];
		// insert code here
		(this.sfTree.nodes[sfID].class == EfxNode).if { prows = prows.insert(1, tbParams) } {};

		prows.do({ |row, index|

			(verbose.isNil.not).if { Post << "row: " << row << "\n"; } {};
			row.do({ |val, index|

				switch (val,
					\srcbufNum,  { row[index+1] = pBuf },
					\savebufNum, { row[index+1] = aBuf },
					\transp,     { row[index+1] = tbTRatio },
					\outbus,     { row[index+1] = currBus },
					\inbus,      { row[index+1] = currBus; currBus = currBus + 1; },
					\dur,        { row[index+1] = tbDur }
				);
			});
			oscList = oscList ++ [[0.02, ([\s_new, srows[index].asSymbol, -1, 1, 0] ++ row).flatten]];
		});
		oscList = oscList ++ [[((tbDur / tbTRatio) + 0.03).unbubble, [\b_write, aBuf, mdpath, "wav", "float32"]]];
		oscList = oscList ++ [[((tbDur / tbTRatio) + 0.04).unbubble, [\c_set, 0, 0]]];

		(verbose.isNil.not).if { oscList.postln; } {};

		Score.recordNRT(
			oscList,
			"/tmp/analyzeNRT.osc",
			"/tmp/dummyOut.aiff",
			options: ServerOptions.new.numOutputBusChannels = 1
		);
		0.01.wait;
		while({
			Post << "ps -xc | grep 'scsynth'" << "\n";
			res = "ps -xc | grep 'scsynth'".systemCmd; //256 if not running, 0 if running
			((timeout % 10) == 0).if { Post << [timeout, res] << "\n" };
			(res == 0) and: {(timeout = timeout - 1) > 0}
		},{
			0.1.wait;
		});
		0.01.wait;
		done = 0;

		thebuffer = Buffer.read(this.server, mdpath, action: { |bfr|
			bfr.loadToFloatArray(action: { |array|

				(verbose.isNil.not).if { Post << "Array 1 (rank/size):" << array.rank << ", " << array.size << ", " << array.flatten.sum << "\n"; };

				ary = array.clump(25).flop;
				ary[0] = ary[0].ampdb.replace( -inf, -120.0);
				//ary[0].postln;
				(0..24).do({ |d|
					ary[d] = ary[d].collect({ |n| n.asStringPrec(4).asFloat });
				});


				// Post << "MDPATH: " << mdpath << "\n";
				this.powers.add(sfID -> ary[0]);
				this.mfccs.add(sfID -> ary[1..].flop);
				// this.activationLayers.add(sfID -> Array.fill(ary[0].size, { 1.0 }));
				// this.powers[sfID].postln;
				// this.mfccs[sfID].collect({|row| row.sum }).postln;
				// this.powers[sfID].size.postln;
				// this.mfccs[sfID].size.postln;

				this.activationLayers.add(sfID -> this.powers[sfID].collect({ |pwr| (pwr <= -120).if { 0 } { 1 } }));
				// this.activationLayers[sfID].postln;
				// (this.activationLayers[sfID].sum.asFloat / this.activationLayers[sfID].size.asFloat).postln;

				this.cookedLayers.add(sfID -> this.mfccs[sfID].collect({ |row, i| row * this.activationLayers[sfID][i] }) );
				// this.cookedLayers[sfID].size.postln;
				done = 1;
			});
		});

		while { done == 0 } { 0.5.wait }; "DONE".postln;
		done = 1;
		aBuf.free; pBuf.free; // "---.md.aiff" saved to disc; free buffers on server

	}

	mapIDToSF { |sfID, path=nil, sfGroup=0|

		var id;

		// (sfID.notNil).if {
		// 	id = sfID;
		// } {
		// 	this.sfOffset = this.sfOffset + 1;
		// 	id = this.sfOffset;
		// };

		(this.sfgMap[sfGroup].isNil).if {
			this.sfgMap.add(sfGroup -> Set[sfID]);
		} {
			this.sfgMap[sfGroup].add(sfID);
		};
		((this.sfMap[sfID].isNil) && (path.isNil.not)).if {
			this.sfMap.add(sfID -> path);
			this.sfMap.add(path -> sfID);
		} {
			Post << "Either this sfid -> path mapping has already been made *OR* you are only mapping a group! Failed to map a path!\n"
			^nil
		};
		^sfID
	}

	addSoundFileUnit { |sfID, onset=0, duration=0, tag=0|

		var id, res;

// 		((sfID.isNil) && (path.isNil.not)).if {
//			id = this.lookupSFID(this.getSoundFilePath(path));
//		} {};
		(verbose.isNil.not).if {
			Post << "SFID: " << sfID << " | onset: " << onset << " | dur: " << duration << "\n";
		};
		(this.sfTree.nodes[sfID].isNil).if {
			Post << "Error: there is no Node for " << sfID << " in the sf tree. Add SFU failed.\n";
			^nil
		} {
			res = this.sfTree.nodes[sfID].addOnsetAndDurPair(onset, duration);
			(verbose.isNil.not).if { Post << "TAG: " << tag << "\n"; };
		};
		this.soundFileUnitsMapped = false;
		^sfID
	}

	updateSoundFileUnit { |sfID, relID=nil, onset=nil, duration=nil, tag=nil|

		var id, res;

// 		((sfID.isNil) && (path.isNil.not)).if {
// 			id = this.lookupSFID(this.getSoundFilePath(path));
// 		} {};
		(relID.isNil.not).if {
			(this.sfTree.nodes[sfID].isNil).if {
				Post << "Error: there is no Node for " << sfID << " in the sf tree. Add SFU failed.\n";
				^nil
			} {
				this.sfTree.nodes[sfID].updateUnitSegmentParams(relID, onset, duration, tag);
				^[sfID, relID]
			};
			// relid can be bad (throws an IndexError in Python version!
		} {};
	}

	removeSoundFileUnit { |sfID, relID=nil, bounds=nil|

		this.sfTree.nodes[sfID].unitSegments[relID] = nil;
		this.sfTree.nodes[sfID].unitSegments.remove(nil);
		^relID
	}

	clearSoundFileUnits { |sfID|
		var id;
// 		((sfID.isNil) && (path.isNil.not)).if {
// 			id = this.lookupSFID(this.getSoundFilePath(path));
// 		} {};
		(this.sfTree.nodes[sfID].isNil).if {
			Post << "Error: there is no Node for " << sfID << " in the sf tree. Add SFU failed.\n";
			^nil
		} {
			this.sfTree.nodes[sfID].unitSegments = List[]; // bad OOP programming!
			^sfID
		};
	}

	getUnitSegment { |sfID, relID|
		(this.sfMap[sfID].isNil.not).if
		{
			^this.sfTree.nodes[this.sfMap[sfID]].unitSegments[relID]
		} {
			^nil
		};
	}

	getSndPath { |fileName| ^(this.anchorPath +/+ "snd" +/+ fileName) } // needs subdir mod

	getRawMetadata { |sfID|

		((this.powers[sfID].isNil) || (this.mfccs[sfID].isNil) || (this.activationLayers.isNil) || (this.cookedLayers.isNil)).if {
			^this.activateRawMetadata(sfID)
		} {
			^[this.powers[sfID], this.mfccs[sfID], this.activationLayers[sfID], this.cookedLayers[sfID]]
		};
	}

	// getSortedUnitsList { |sfID| ^this.sfTree.nodes[sfID].sortSegmentsList }

	segmentUnits { |sfID|

		var segList, rawamps, cookedmfccs;

		segList = this.sfTree.nodes[sfID].unitSegments.sort({ |a,b| a.onset < b.onset });
		rawamps = this.powers[sfID];
		cookedmfccs = this.cookedLayers[sfID];
		// "cooked size: ".post; cookedmfccs.size.postln;
		// "cooked size - 0: ".post; cookedmfccs[0].size.postln;
		segList.do({ |sfu, relid|

			var offset, dur;
			// sfu.class.postln;
			// sfu.postln;
			offset = (sfu.onset / this.hopSeconds).asInteger;
			dur = (sfu.duration / this.hopSeconds).asInteger;
			// Post << offset << " | " << dur << "    " << cookedmfccs[offset..(offset+dur-1)][0].size << "\n"; //.collect({|col, i| col.mean }).flop.size <<"\n";
			this.sfTree.nodes[sfID].addMetadataForRelID(
				relid,
				this.analyzeScalar(rawamps, offset, dur),
				cookedmfccs[offset..(offset+dur-1)].flop.collect({|col, i| col.mean }).flop
			);
		});
	}

	analyzeScalar { |raw, offset, duration|
		var chopped, mean, max, lval, rval, slope;
		// "RAW: ".post; raw.postln;
		chopped = raw[offset..(offset+duration-1)];
		// "CHOPPED: ".post; chopped.postln;
		mean = chopped.mean;
		max = chopped.maxItem;
		lval = chopped[..2].mean;
		rval = chopped[(chopped.size-2)..].mean;
		slope = (rval - lval) / duration;
		^[mean, max, lval, rval, slope]
	}

	addCorpusUnit { |uid, metadata| this.cuTable.add(uid -> metadata) }
	removeCorpusUnit { |uid| this.cuTable.add(uid -> nil) }
	clearCorpusUnits { this.cuTable = Dictionary[] }

	lookupSFID { |path|
		(this.sfMap[path].isNil).if {
			Post << "Error: there is no entry for " << path << " in the sf map. Lookup SFID failed.\n";
			^nil
		} {
			^this.sfMap[path]
		};
	}

	lookupPath { |sfID|
		(this.sfMap[sfID].isNil).if {
			Post << "Error: there is no entry for " << sfID << " in the sf map. Lookup SFID failed.\n";
			^nil
		} {
			^this.sfMap[sfID]
		};
	}

	getSoundFileUnitMetadata { |sfID|
		^this.cuTable.detect({ |item, i| (item[2] == sfID) }); // is this enough?
	}

	mapSoundFileUnitsToCorpusUnits {

		var sfnodes = this.sfTree.nodes;
		"map sound file units to corpus units".postln;
		this.clearCorpusUnits;
		// sfnodes.keys.postln;

		sfnodes.keys.asArray.sort.do({ |nid|
			var sfID, sfgrp, sftratio, sfunitsegs, relid, ampSeg, mfccsSeg, index, row, node;
			// nid.postln;
			node = sfnodes[nid];
			// node.postln;
			sfID = node.sfID;
			sfgrp = node.group;
			sftratio = node.tRatio;
			sfunitsegs = node.unitSegments;

			node.unitAmps.keys.asArray.sort.do({ |k, relid|
				// k.postln;
				ampSeg = node.unitAmps[k];
				mfccsSeg = node.unitMFCCs[k];
				index = this.cuOffset;

				row = [index, relid, sfID, sfgrp, sfunitsegs[relid].onset, sfunitsegs[relid].duration, sftratio, sfunitsegs[relid].tag];
				// Post << "INDEX: " << index << "\n";
				this.addCorpusUnit(index, (row ++ ampSeg ++ mfccsSeg).flat);
				this.cuOffset = this.cuOffset + 1;

			});
		});
	}



	//####*****
	//#
	//# CORPUS RAW ARRAY ACCESS
	//#
	//
	//#	 index (8)         amp (5)        mfccs (24)
	//#	[0 1 2 3 4 5 6 7] [8 9 10 11 12] [13 14 15 16 17 ... 36]
	//#

	convertCorpusToArray { |type="all", mapFlag=false|

		var numDescriptors, xlist;

		(mapFlag).if { this.mapSoundFileUnitsToCorpusUnits; } { };
		numDescriptors = this.cuTable[0].size;

		xlist = List[];
		this.cuTable.keys.asArray.sort.do({ |cuid|
			xlist = xlist ++ [this.cuTable[cuid]];
		});
		// Post << "X's size: " << xlist.size << "\n";

		(type == "I").if { ^xlist.flop[..7].flop } {};
		(type == "A").if { ^xlist.flop[8..12].flop } {};
		(type == "M").if { ^xlist.flop[13..].flop } {};
		(type == "all").if { ^xlist } {};
	}

	convertCorpusToTaggedArray { |tag=0, mapFlag=false|

		var info, sliced, fullCUTable, filteredByTag;
		// get the info segments and filter those that are tagged
		info = this.convertCorpusToArray("I", mapFlag);

		fullCUTable = this.convertCorpusToArray("all");
		sliced = fullCUTable.collect({|unit| (unit[7] == tag).if {unit} {nil} });
		^sliced.removeAllSuchThat({|item| item.isNil.not })

	}

	cuidsForSFID { |sfID| ^this.cuTable.items.asArray.select({|cunit| cunit[2] == sfID }).flop[0].sort }

	exportCorpusToJSON { |jsonPath|

		var f, toplevel, sf, d;
		f = File.open(jsonPath, "w");
		toplevel = Dictionary["descriptors" -> this.dTable ];
		sf = Dictionary[];
		this.sfTree.nodes.keys.do({ |sfid|
			// "-------------------------------------".postln;
			// sfid.post; " --->".postln;
			// this.sfTree.nodes[sfid].jsonRepr.postln;
			// "=====================================".postln;
			sf.add(sfid.asString -> this.sfTree.nodes[sfid].jsonRepr);
			toplevel.add("soundfiletree" -> sf);
		});
		// roll the cutable rows into dictionary
		d = Dictionary[];
		// Post << "keys: " << this.cuTable.keys.asArray.sort;
		this.cuTable.keys.asArray.sort.do({ |cid|
			// Post << "cutable entry: " << this.cuTable[cid].class << "\n";
			d.add(cid.asString -> this.cuTable[cid].asFloat);
		});
		toplevel.add("corpusunits" -> d);
		// toplevel.postln;
		JSONSerializer.writeToFile(toplevel, f);
		f.close;
	}

	importCorpusFromJSON { |jsonPath, appendFlag=nil, importFlag=nil|

		var jsonString, soundfiles, corpusunits;

		(appendFlag.isNil).if {
			Post << "appendflag is FALSE\n" << "sf offset: " << this.sfOffset << "\n" << "Resetting corpus!\n";
			this.resetCorpus;

		} {
 			Post << "appendflag is TRUE\n" << "Starting @ sf offset: " << this.sfOffset << "\n";
		};
		// set up
		File.use(jsonPath, "r", { |f|

			jsonString = f.readAllString.parseJson;
		});

		soundfiles = jsonString["soundfiletree"];
		soundfiles.keys.asArray.asInteger.sort.do({ |key|
			var sf, pkey, pid, sfid, fullpath, filename;
			sf = soundfiles[key.asString];
			// sf.keys.postln;
			(sf.includesKey("parentid")).if {

				pid = sf["parentid"];

				// Post << key << " | " << sf["sfid"] << " | " << pid << "\n";
				// "-------------".postln;
				sfid = this.addSoundFile(filePath:nil,
					sfid:(key.asInteger + this.sfOffset), //sf["sfid"].asInteger + this.sfOffset
					srcFileID:(pid.asInteger + this.sfOffset),
					tRatio:sf["tratio"].asFloat,
					sfGrpID:sf["group"].asInteger,
					synthdef:sf["synth"].asString,
					params:sf["params"],
					uflag:sf["uniqueid"].asInteger
				);

			} {
				fullpath = PathName.new(sf["path"].asString);
				filename = fullpath.fullPath.asString;

				// Post << key << " | " << sf["sfid"] << " | " << sf["group"] << " | " << sf["uniqueid"] << "\n";
				// "-------------".postln;
				sfid = this.addSoundFile(filePath:filename,
					sfid:(key.asInteger + this.sfOffset), //sf["sfid"].asInteger + this.sfOffset
					srcFileID:nil,
					tRatio:sf["tratio"].asFloat,
					sfGrpID:sf["group"].asInteger,
					importFlag:importFlag,
					uflag:sf["uniqueid"].asInteger
				);
			};
		});


		corpusunits = jsonString["corpusunits"];
		corpusunits.keys.asArray.do({ |key|
			var cunit = corpusunits[key][1..].reverse[1..].reverse.split($,).asFloat;
			// ugly hack that works!
			// "CUNIT: ".post;
			// cunit.postcs;
			// "\n".post;
			cunit[0] = cunit[0].asInteger + this.cuOffset;
			cunit[2] = cunit[2].asInteger + this.sfOffset;
			// Post << cunit[0] << " | " << cunit[2] << "\n";
			this.addCorpusUnit((key.asInteger + this.cuOffset), cunit);
		});


		this.sfOffset = this.sfTree.nodes.keys.maxItem + 1;
		Post << "UPDATE max sfid --> sf_offset: " << this.sfOffset << "\n";
		this.cuOffset = this.cuTable.keys.maxItem + 1;
		Post << "UPDATE max cutable key --> cu_offset: " << this.cuOffset << "\n";

	}
}


		